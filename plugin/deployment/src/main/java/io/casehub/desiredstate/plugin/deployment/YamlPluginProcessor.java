package io.casehub.desiredstate.plugin.deployment;

import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.plugin.model.PluginModel;
import io.casehub.desiredstate.plugin.model.PluginParser;
import io.casehub.desiredstate.plugin.runtime.PluginValidator;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import io.quarkus.deployment.annotations.BuildProducer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

public class YamlPluginProcessor {

    private static final String PLUGIN_PATH = "META-INF/desiredstate/plugins/";
    private static final DotName NODE_TYPE_ID = DotName.createSimple(NodeTypeId.class.getName());

    @BuildStep
    void discoverAndValidatePlugins(
            CombinedIndexBuildItem combinedIndex,
            BuildProducer<PluginBuildItem> pluginItems) throws IOException {

        Map<String, String> typeRegistry = scanNodeTypes(combinedIndex.getIndex());
        Set<String> seenPluginTypes = new HashSet<>();

        List<NamedPlugin> discovered = discoverPlugins();
        for (NamedPlugin np : discovered) {
            String type = np.model.header().type();

            if (!seenPluginTypes.add(type)) {
                throw new io.casehub.desiredstate.plugin.runtime.PluginValidationException(type,
                    "Duplicate plugin type — another plugin YAML already declares this type");
            }

            PluginValidator.validatePlugin(np.model, typeRegistry, PluginValidator.BUILT_IN_PRIMITIVES);
            pluginItems.produce(new PluginBuildItem(np.fileName, np.model));
        }
    }

    static Map<String, String> scanNodeTypes(IndexView index) {
        java.util.HashMap<String, String> registry = new java.util.HashMap<>();
        for (AnnotationInstance ann : index.getAnnotations(NODE_TYPE_ID)) {
            String typeId = ann.value().asString();
            String className = ann.target().asClass().name().toString();
            String existing = registry.put(typeId, className);
            if (existing != null) {
                throw new RuntimeException("Duplicate @NodeTypeId(\"" + typeId
                    + "\"): " + existing + " and " + className);
            }
        }
        return registry;
    }

    // --- discovery ---

    private List<NamedPlugin> discoverPlugins() throws IOException {
        List<NamedPlugin> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> urls = cl.getResources(PLUGIN_PATH);

        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            String protocol = url.getProtocol();
            if ("file".equals(protocol)) {
                discoverFromDirectory(new File(url.getPath()), results, seen);
            } else if ("jar".equals(protocol)) {
                discoverFromJar(url, results, seen);
            }
        }
        return results;
    }

    private void discoverFromDirectory(File dir, List<NamedPlugin> results,
                                       Set<String> seen) throws IOException {
        File[] files = dir.listFiles((d, name) ->
            name.endsWith(".yaml") || name.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            if (!seen.add(file.getName())) continue;
            try (InputStream is = new FileInputStream(file)) {
                PluginModel model = PluginParser.parse(is);
                results.add(new NamedPlugin(file.getName(), model));
            }
        }
    }

    private void discoverFromJar(URL url, List<NamedPlugin> results,
                                  Set<String> seen) throws IOException {
        String jarPath = url.getPath();
        int bangIdx = jarPath.indexOf('!');
        if (bangIdx < 0) return;

        String jarFile = jarPath.substring(jarPath.startsWith("file:") ? 5 : 0, bangIdx);
        try (JarInputStream jis = new JarInputStream(new FileInputStream(jarFile))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith(PLUGIN_PATH) && !name.equals(PLUGIN_PATH)
                        && (name.endsWith(".yaml") || name.endsWith(".yml"))) {
                    String fileName = name.substring(PLUGIN_PATH.length());
                    if (!seen.add(fileName)) continue;
                    PluginModel model = PluginParser.parse(jis);
                    results.add(new NamedPlugin(fileName, model));
                }
            }
        }
    }

    record NamedPlugin(String fileName, PluginModel model) {}
}
