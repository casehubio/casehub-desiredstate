package io.casehub.desiredstate.plugin.spring;

import io.casehub.desiredstate.api.BeanRegistration;
import io.casehub.desiredstate.plugin.model.PluginModel;
import io.casehub.desiredstate.plugin.model.PluginParser;
import io.casehub.desiredstate.plugin.runtime.PluginDescriptor;
import io.casehub.desiredstate.plugin.runtime.PluginDiscovery;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@AutoConfiguration
@ConditionalOnClass(PluginDescriptor.class)
public class DesiredStatePluginAutoConfiguration implements SmartInitializingSingleton {

    private final GenericApplicationContext context;

    public DesiredStatePluginAutoConfiguration(GenericApplicationContext context) {
        this.context = context;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            List<PluginModel> plugins = discoverPlugins();
            new PluginDiscovery().discover(plugins)
                                 .forEach(this::registerBean);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to discover desired state plugins", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void registerBean(BeanRegistration reg) {
        context.registerBean(reg.name(), (Class<T>) reg.type(), () -> (T) reg.instance());
    }

    private List<PluginModel> discoverPlugins() throws IOException {
        List<PluginModel>                   plugins  = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        for (String pattern : List.of("classpath*:META-INF/desiredstate/plugins/*.yaml",
                                      "classpath*:META-INF/desiredstate/plugins/*.yml")) {
            for (Resource resource : resolver.getResources(pattern)) {
                if (resource.isReadable()) {
                    try (InputStream is = resource.getInputStream()) {
                        PluginModel model = PluginParser.parse(is);
                        plugins.add(model);
                    }
                }
            }
        }
        return plugins;
    }
}
