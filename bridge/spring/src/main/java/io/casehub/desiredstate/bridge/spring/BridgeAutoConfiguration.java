package io.casehub.desiredstate.bridge.spring;

import io.casehub.desiredstate.bridge.BridgeDescriptor;
import io.casehub.desiredstate.bridge.BridgeProvider;
import io.casehub.desiredstate.bridge.runtime.BridgeDiscovery;
import io.casehub.desiredstate.bridge.runtime.BridgeManifestParser;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

@AutoConfiguration
@ConditionalOnClass(BridgeProvider.class)
public class BridgeAutoConfiguration implements SmartInitializingSingleton {

    private final ApplicationContext context;

    public BridgeAutoConfiguration(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void afterSingletonsInstantiated() {
        var descriptors = discoverManifests(context.getClassLoader());
        if (descriptors.isEmpty()) return;

        var providers = new ArrayList<>(context.getBeansOfType(BridgeProvider.class).values());
        var beans = BridgeDiscovery.discoverAll(descriptors, providers);

        if (context instanceof ConfigurableApplicationContext configCtx) {
            ConfigurableListableBeanFactory beanFactory = configCtx.getBeanFactory();
            for (var bean : beans) {
                beanFactory.registerSingleton(bean.name(), bean.instance());
            }
        }
    }

    static List<BridgeDescriptor> discoverManifests(ClassLoader classLoader) {
        List<BridgeDescriptor> descriptors = new ArrayList<>();
        try {
            Enumeration<URL> urls = classLoader.getResources("META-INF/desiredstate/bridges/");
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                try (InputStream is = url.openStream()) {
                    if (is != null) {
                        descriptors.add(BridgeManifestParser.parse(is));
                    }
                } catch (Exception e) {
                    // skip malformed manifests
                }
            }
        } catch (IOException e) {
            // no manifests on classpath
        }
        return descriptors;
    }
}
