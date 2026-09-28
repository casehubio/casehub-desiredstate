package io.casehub.desiredstate.plugin.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.plugin.model.PluginModel;
import io.casehub.desiredstate.plugin.model.PluginParser;
import io.casehub.desiredstate.plugin.runtime.PluginDescriptor;
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
import java.util.logging.Logger;

@AutoConfiguration
@ConditionalOnClass(PluginDescriptor.class)
public class DesiredStatePluginAutoConfiguration implements SmartInitializingSingleton {

    private static final Logger LOG = Logger.getLogger(DesiredStatePluginAutoConfiguration.class.getName());

    private final GenericApplicationContext context;

    public DesiredStatePluginAutoConfiguration(GenericApplicationContext context) {
        this.context = context;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            List<PluginModel> plugins = discoverPlugins();
            if (plugins.isEmpty()) {
                return;
            }

            for (PluginModel plugin : plugins) {
                String type = plugin.header().type();
                PluginDescriptor descriptor = new PluginDescriptor(
                        type, plugin.header(), plugin.spec(), plugin.provisioner(),
                        plugin.faultPolicy(), plugin.cbr(), plugin.ras());
                String beanName = "pluginDescriptor_" + type;
                context.registerBean(beanName, PluginDescriptor.class, () -> descriptor);
                LOG.fine("Registered plugin descriptor: " + type);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to discover desired state plugins", e);
        }
    }

    private List<PluginModel> discoverPlugins() throws IOException {
        List<PluginModel> plugins = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

        for (String pattern : List.of("classpath*:META-INF/desiredstate/plugins/*.yaml",
                                       "classpath*:META-INF/desiredstate/plugins/*.yml")) {
            for (Resource resource : resolver.getResources(pattern)) {
                if (resource.isReadable()) {
                    try (InputStream is = resource.getInputStream()) {
                        PluginModel model = PluginParser.parse(yamlMapper, is);
                        plugins.add(model);
                    }
                }
            }
        }
        return plugins;
    }
}
