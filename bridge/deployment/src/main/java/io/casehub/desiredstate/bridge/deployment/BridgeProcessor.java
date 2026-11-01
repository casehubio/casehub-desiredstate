package io.casehub.desiredstate.bridge.deployment;

import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.bridge.BridgeDescriptor;
import io.casehub.desiredstate.bridge.runtime.BridgeManifestParser;
import io.casehub.desiredstate.bridge.runtime.BridgeRecorder;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.runtime.RuntimeValue;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

public class BridgeProcessor {

    private static final Logger LOG                    = Logger.getLogger(BridgeProcessor.class);
    private static final String BRIDGE_MANIFEST_PREFIX = "META-INF/desiredstate/bridges/";

    @BuildStep
    NativeImageResourceBuildItem registerBridgeManifests() {
        return new NativeImageResourceBuildItem(List.of(BRIDGE_MANIFEST_PREFIX));
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void discoverBridgeManifests(BridgeRecorder recorder,
                                 BuildProducer<SyntheticBeanBuildItem> syntheticBeans) throws IOException {
        List<BridgeDescriptor> descriptors = discoverManifests();
        if (descriptors.isEmpty()) {
            LOG.debug("No bridge manifests found at " + BRIDGE_MANIFEST_PREFIX);
            return;
        }

        for (var descriptor : descriptors) {
            RuntimeValue<NodeProvisioner> provisioner = recorder.createProvisioner(descriptor);
            syntheticBeans.produce(SyntheticBeanBuildItem.configure(NodeProvisioner.class)
                                                         .scope(ApplicationScoped.class)
                                                         .unremovable()
                                                         .setRuntimeInit()
                                                         .runtimeValue(provisioner)
                                                         .done());

            RuntimeValue<ActualStateAdapter> adapter = recorder.createActualStateAdapter(descriptor);
            syntheticBeans.produce(SyntheticBeanBuildItem.configure(ActualStateAdapter.class)
                                                         .scope(ApplicationScoped.class)
                                                         .unremovable()
                                                         .setRuntimeInit()
                                                         .runtimeValue(adapter)
                                                         .done());

            LOG.debugf("Registered bridge beans for protocol '%s' with %d types",
                       descriptor.protocolId(), descriptor.handledTypes().size());
        }
    }

    static List<BridgeDescriptor> discoverManifests() throws IOException {
        List<BridgeDescriptor> descriptors = new ArrayList<>();
        Set<String>            seen        = new HashSet<>();
        ClassLoader            cl          = Thread.currentThread().getContextClassLoader();
        Enumeration<URL>       urls        = cl.getResources(BRIDGE_MANIFEST_PREFIX);

        while (urls.hasMoreElements()) {
            URL    url      = urls.nextElement();
            String protocol = url.getProtocol();
            if ("file".equals(protocol)) {
                discoverFromDirectory(new File(url.getPath()), descriptors, seen);
            } else if ("jar".equals(protocol)) {
                discoverFromJar(url, descriptors, seen);
            }
        }
        return descriptors;
    }

    private static void discoverFromDirectory(File dir, List<BridgeDescriptor> results,
                                              Set<String> seen) throws IOException {
        File[] files = dir.listFiles((d, name) ->
                                             name.endsWith(".yaml") || name.endsWith(".yml"));
        if (files == null) {return;}

        for (File file : files) {
            if (!seen.add(file.getName())) {continue;}
            try (InputStream is = new FileInputStream(file)) {
                results.add(BridgeManifestParser.parse(is));
            }
        }
    }

    private static void discoverFromJar(URL url, List<BridgeDescriptor> results,
                                        Set<String> seen) throws IOException {
        String jarPath = url.getPath();
        int    bangIdx = jarPath.indexOf('!');
        if (bangIdx < 0) {return;}

        String jarFile = jarPath.substring(jarPath.startsWith("file:") ? 5 : 0, bangIdx);
        try (JarInputStream jis = new JarInputStream(new FileInputStream(jarFile))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith(BRIDGE_MANIFEST_PREFIX) && !name.equals(BRIDGE_MANIFEST_PREFIX)
                    && (name.endsWith(".yaml") || name.endsWith(".yml"))) {
                    String fileName = name.substring(BRIDGE_MANIFEST_PREFIX.length());
                    if (!seen.add(fileName)) {continue;}
                    results.add(BridgeManifestParser.parse(jis));
                }
            }
        }
    }
}
