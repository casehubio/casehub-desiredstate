package io.casehub.desiredstate.plugin.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.plugin.model.PluginModel;
import io.casehub.desiredstate.plugin.model.PluginParser;
import io.casehub.platform.api.credentials.CredentialResolver;
import io.casehub.yaml.core.condition.ConditionEvaluator;
import io.casehub.yaml.plugin.api.MapServiceRegistry;
import io.casehub.yaml.plugin.api.PluginRegistry;
import io.casehub.yaml.plugin.api.ServiceRegistry;
import io.casehub.yaml.step.catalog.AptPluginSource;
import io.casehub.yaml.step.catalog.CompositePluginRegistry;
import io.casehub.yaml.step.eval.CatalogStepRunner;
import io.casehub.yaml.step.eval.StepRunner;
import io.casehub.yaml.step.eval.StructuralStepEvaluator;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@ApplicationScoped
public class PluginRuntimeBeans {

    private static final Logger LOG = Logger.getLogger(PluginRuntimeBeans.class.getName());
    private static final String PLUGIN_PATH = "META-INF/desiredstate/plugins/";

    @Produces
    @DefaultBean
    @ApplicationScoped
    PluginRegistry pluginRegistry(Instance<ConditionEvaluator> conditionEvaluators,
                                  Instance<ObjectMapper> objectMappers) {
        var registry = new CompositePluginRegistry();
        var mapper = objectMappers.isResolvable()
                ? objectMappers.get() : new ObjectMapper();
        new AptPluginSource(mapper).populate(registry);
        var condEval = conditionEvaluators.isResolvable()
                ? conditionEvaluators.get()
                : new ConditionEvaluator(expr -> Boolean.parseBoolean(expr.trim()));
        new DesiredStatePrimitiveRegistrar(condEval, mapper,
                DesiredStatePrimitiveRegistrar.logWriter()).registerAll(registry);
        return registry;
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    StepRunner stepRunner(PluginRegistry registry, Instance<ServiceRegistry> serviceRegistries) {
        var services = serviceRegistries.isResolvable()
                ? serviceRegistries.get()
                : new MapServiceRegistry();
        return new CatalogStepRunner(registry, services);
    }

    @Produces
    @DefaultBean
    @Singleton
    NodeProvisioner yamlPluginProvisioner(StepRunner runner,
                                          Instance<ConditionEvaluator> conditionEvaluators,
                                          Instance<CredentialResolver> credentialResolvers) {
        Map<NodeType, PluginDescriptor> descriptors = discoverDescriptorMap();
        var condEval = conditionEvaluators.isResolvable()
                ? conditionEvaluators.get()
                : new ConditionEvaluator(expr -> Boolean.parseBoolean(expr.trim()));
        var evaluator = new StructuralStepEvaluator(condEval);
        var credResolver = credentialResolvers.isResolvable()
                ? credentialResolvers.get()
                : (CredentialResolver) ref -> Map.of();
        return new YamlPluginProvisioner(descriptors, evaluator, runner, credResolver);
    }

    @Produces
    @DefaultBean
    @Singleton
    ActualStateAdapter yamlPluginActualStateAdapter(
            StepRunner runner,
            Instance<ConditionEvaluator> conditionEvaluators,
            Instance<CredentialResolver> credentialResolvers) {
        Map<NodeType, PluginDescriptor> descriptors = discoverDescriptorMap();
        var condEval = conditionEvaluators.isResolvable()
                ? conditionEvaluators.get()
                : new ConditionEvaluator(expr -> Boolean.parseBoolean(expr.trim()));
        var evaluator = new StructuralStepEvaluator(condEval);
        var credResolver = credentialResolvers.isResolvable()
                ? credentialResolvers.get()
                : (CredentialResolver) ref -> Map.of();
        return new YamlPluginActualStateAdapter(descriptors, evaluator, runner, credResolver);
    }

    private Map<NodeType, PluginDescriptor> discoverDescriptorMap() {
        Map<NodeType, PluginDescriptor> result = new LinkedHashMap<>();
        try {
            var discovery = new PluginDiscovery();
            for (PluginModel model : discoverPluginModels()) {
                for (var bean : discovery.discover(List.of(model))) {
                    if (bean.instance() instanceof PluginDescriptor desc) {
                        result.put(NodeType.of(desc.type()), desc);
                    }
                }
            }
        } catch (IOException e) {
            LOG.severe("Failed to discover YAML plugins: " + e.getMessage());
        }
        return result;
    }

    private List<PluginModel> discoverPluginModels() throws IOException {
        List<PluginModel> models = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> urls = cl.getResources(PLUGIN_PATH);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if ("file".equals(url.getProtocol())) {
                java.io.File dir = new java.io.File(url.getPath());
                java.io.File[] files = dir.listFiles(
                        (d, name) -> name.endsWith(".yaml") || name.endsWith(".yml"));
                if (files != null) {
                    for (java.io.File file : files) {
                        try (InputStream is = new java.io.FileInputStream(file)) {
                            models.add(PluginParser.parse(is));
                        }
                    }
                }
            }
        }
        return models;
    }
}
