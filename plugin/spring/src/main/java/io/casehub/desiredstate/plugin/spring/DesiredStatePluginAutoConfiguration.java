package io.casehub.desiredstate.plugin.spring;

import io.casehub.desiredstate.api.BeanRegistration;
import io.casehub.desiredstate.plugin.model.PluginModel;
import io.casehub.desiredstate.plugin.model.PluginParser;
import io.casehub.desiredstate.plugin.runtime.DesiredStatePrimitiveRegistrar;
import io.casehub.desiredstate.plugin.runtime.PluginDescriptor;
import io.casehub.desiredstate.plugin.runtime.PluginDiscovery;
import io.casehub.yaml.core.condition.ConditionEvaluator;
import io.casehub.yaml.plugin.api.MapServiceRegistry;
import io.casehub.yaml.plugin.api.PluginRegistry;
import io.casehub.yaml.plugin.api.ServiceRegistry;
import io.casehub.yaml.step.catalog.AptPluginSource;
import io.casehub.yaml.step.catalog.CompositePluginRegistry;
import io.casehub.yaml.step.eval.CatalogStepRunner;
import io.casehub.yaml.step.eval.StepRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

@AutoConfiguration
@ConditionalOnClass(PluginDescriptor.class)
public class DesiredStatePluginAutoConfiguration implements SmartInitializingSingleton {

    private static final Logger LOG = Logger.getLogger(
            DesiredStatePluginAutoConfiguration.class.getName());

    private final GenericApplicationContext context;

    public DesiredStatePluginAutoConfiguration(GenericApplicationContext context) {
        this.context = context;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            List<PluginModel> plugins = discoverPlugins();
            var runner = context.getBean(StepRunner.class);
            var condEval = context.getBeanProvider(ConditionEvaluator.class)
                    .getIfAvailable(() -> new ConditionEvaluator(
                            expr -> Boolean.parseBoolean(expr.trim())));
            var evaluator = new io.casehub.yaml.step.eval.StructuralStepEvaluator(condEval);
            var credResolver = context.getBeanProvider(
                    io.casehub.platform.api.credentials.CredentialResolver.class)
                    .getIfAvailable(() -> ref -> java.util.Map.of());
            new PluginDiscovery().discoverAll(plugins, evaluator, runner, credResolver)
                                 .forEach(this::registerBean);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to discover desired state plugins", e);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    PluginRegistry pluginRegistry(ObjectProvider<ConditionEvaluator> conditionEvaluators,
                                  ObjectProvider<ObjectMapper> objectMappers) {
        var registry = new CompositePluginRegistry();
        var mapper = objectMappers.getIfAvailable(ObjectMapper::new);
        new AptPluginSource(mapper).populate(registry);
        var condEval = conditionEvaluators.getIfAvailable(
                () -> new ConditionEvaluator(expr -> Boolean.parseBoolean(expr.trim())));
        new DesiredStatePrimitiveRegistrar(condEval, mapper,
                DesiredStatePrimitiveRegistrar.logWriter()).registerAll(registry);
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    StepRunner stepRunner(PluginRegistry registry,
                          ObjectProvider<ServiceRegistry> serviceRegistries) {
        var services = serviceRegistries.getIfAvailable(MapServiceRegistry::new);
        return new CatalogStepRunner(registry, services);
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
