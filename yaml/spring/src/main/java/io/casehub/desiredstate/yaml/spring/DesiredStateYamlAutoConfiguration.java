package io.casehub.desiredstate.yaml.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import io.casehub.desiredstate.api.BeanRegistration;
import io.casehub.desiredstate.runtime.spring.SpringJandexSupport;
import io.casehub.desiredstate.yaml.YamlDiscovery;
import io.casehub.desiredstate.yaml.YamlGoalCompilerFactory;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import io.casehub.yaml.core.module.ModuleExpander;
import io.casehub.yaml.core.module.YamlModule;
import io.casehub.yaml.core.module.YamlModuleFile;
import io.casehub.yaml.jackson.YamlCoreJacksonModule;
import org.jboss.jandex.IndexView;
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
import java.util.Map;

@AutoConfiguration
@ConditionalOnClass(YamlGoalCompilerFactory.class)
public class DesiredStateYamlAutoConfiguration implements SmartInitializingSingleton {

    private final GenericApplicationContext context;

    public DesiredStateYamlAutoConfiguration(GenericApplicationContext context) {
        this.context = context;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            IndexView           index        = SpringJandexSupport.loadCompositeIndex();
            Map<String, String> typeRegistry = SpringJandexSupport.scanNodeTypes(index);
            if (typeRegistry.isEmpty()) {
                return;
            }

            ObjectMapper yamlMapper = new ObjectMapper(YAMLFactory.builder()
                                                                  .enable(YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS).build());
            List<YamlGraph>         graphs  = discoverYamlGraphs(yamlMapper);
            Map<String, YamlModule> modules = discoverModules(yamlMapper);

            new YamlDiscovery().discover(graphs, typeRegistry, modules)
                               .forEach(this::registerBean);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to discover YAML desired state graphs", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void registerBean(BeanRegistration reg) {
        context.registerBean(reg.name(), (Class<T>) reg.type(), () -> (T) reg.instance());
    }

    private List<YamlGraph> discoverYamlGraphs(ObjectMapper mapper) throws IOException {
        List<YamlGraph>                     graphs   = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (String pattern : List.of("classpath*:META-INF/desiredstate/*.yaml",
                                      "classpath*:META-INF/desiredstate/*.yml")) {
            for (Resource resource : resolver.getResources(pattern)) {
                if (resource.isReadable()) {
                    try (InputStream is = resource.getInputStream()) {
                        graphs.add(mapper.readValue(is, YamlGraph.class));
                    }
                }
            }
        }
        return graphs;
    }

    private Map<String, YamlModule> discoverModules(ObjectMapper mapper) throws IOException {
        ObjectMapper moduleMapper = mapper.copy();
        moduleMapper.registerModule(new YamlCoreJacksonModule());

        List<YamlModuleFile>                moduleFiles = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver    = new PathMatchingResourcePatternResolver();
        for (Resource resource : resolver.getResources("classpath*:META-INF/desiredstate/modules/*.yaml")) {
            if (resource.isReadable()) {
                try (InputStream is = resource.getInputStream()) {
                    moduleFiles.add(moduleMapper.readValue(is, YamlModuleFile.class));
                }
            }
        }
        return ModuleExpander.resolveExtensions(moduleFiles);
    }
}
