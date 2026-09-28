package io.casehub.desiredstate.yaml.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.annotations.runtime.ResolvedInvariant;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.InMemoryFaultCountStore;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.api.ThresholdFaultPolicy;
import io.casehub.desiredstate.yaml.YamlFaultPolicyBuilder;
import io.casehub.desiredstate.yaml.YamlGoalCompilerFactory;
import io.casehub.desiredstate.yaml.YamlInvariantConverter;
import io.casehub.desiredstate.yaml.model.YamlFaultPolicy;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import io.casehub.desiredstate.yaml.model.YamlInvariant;
import io.casehub.desiredstate.yaml.model.YamlNode;
import io.casehub.yaml.core.module.ModuleExpander;
import io.casehub.yaml.core.module.YamlModule;
import io.casehub.yaml.core.module.YamlModuleFile;
import io.casehub.yaml.jackson.YamlCoreJacksonModule;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.IndexView;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoConfiguration
@ConditionalOnClass(YamlGoalCompilerFactory.class)
public class DesiredStateYamlAutoConfiguration implements SmartInitializingSingleton {

    private static final DotName NODE_SPEC = DotName.createSimple(NodeSpec.class.getName());
    private static final DotName NODE_TYPE_ID = DotName.createSimple(NodeTypeId.class.getName());

    private final GenericApplicationContext context;

    public DesiredStateYamlAutoConfiguration(GenericApplicationContext context) {
        this.context = context;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            IndexView index = loadCompositeJandexIndex();
            Map<String, String> typeRegistry = scanNodeTypes(index);
            if (typeRegistry.isEmpty()) {
                return;
            }

            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            List<YamlGraph> graphs = discoverYamlGraphs(yamlMapper);
            Map<String, YamlModule> modules = discoverModules(yamlMapper);

            for (YamlGraph yamlGraph : graphs) {
                String ns = yamlGraph.desiredState().namespace();
                String name = yamlGraph.desiredState().name();

                List<ResolvedInvariant> invariants = buildInvariants(yamlGraph.invariants());

                if (yamlGraph.lifecycle() != null) {
                    GoalCompiler<?> compiler = YamlGoalCompilerFactory.createLifecycle(
                            yamlGraph, typeRegistry,
                            yamlGraph.variables() != null ? yamlGraph.variables() : Map.of(),
                            invariants);
                    String beanName = "goalCompiler_yaml_" + ns + "_" + name;
                    context.registerBean(beanName, GoalCompiler.class, () -> compiler);
                } else {
                    GraphDescriptor descriptor = toGraphDescriptor(yamlGraph, typeRegistry);
                    GoalCompiler<?> compiler = YamlGoalCompilerFactory.create(
                            descriptor, typeRegistry,
                            yamlGraph.variables() != null ? yamlGraph.variables() : Map.of(),
                            invariants, yamlGraph, modules, List.of(), List.of());
                    String beanName = "goalCompiler_yaml_" + ns + "_" + name;
                    context.registerBean(beanName, GoalCompiler.class, () -> compiler);
                }

                for (int i = 0; i < yamlGraph.faultPolicy().size(); i++) {
                    YamlFaultPolicy yamlPolicy = yamlGraph.faultPolicy().get(i);
                    ThresholdFaultPolicy policy = YamlFaultPolicyBuilder.build(
                            yamlPolicy, typeRegistry, new InMemoryFaultCountStore());
                    String beanName = "faultPolicy_yaml_" + ns + "_" + yamlPolicy.namespace();
                    context.registerBean(beanName, FaultPolicy.class, () -> policy);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to discover YAML desired state graphs", e);
        }
    }

    private Map<String, String> scanNodeTypes(IndexView index) {
        Map<String, String> registry = new HashMap<>();
        for (AnnotationInstance ann : index.getAnnotations(NODE_TYPE_ID)) {
            if (ann.target().kind() == org.jboss.jandex.AnnotationTarget.Kind.CLASS) {
                ClassInfo cls = ann.target().asClass();
                if (index.getAllKnownImplementors(NODE_SPEC).contains(cls)) {
                    String typeId = ann.value().asString();
                    registry.put(typeId, cls.name().toString());
                }
            }
        }
        return registry;
    }

    private List<YamlGraph> discoverYamlGraphs(ObjectMapper mapper) throws IOException {
        List<YamlGraph> graphs = new ArrayList<>();
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

        List<YamlModuleFile> moduleFiles = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (Resource resource : resolver.getResources("classpath*:META-INF/desiredstate/modules/*.yaml")) {
            if (resource.isReadable()) {
                try (InputStream is = resource.getInputStream()) {
                    moduleFiles.add(moduleMapper.readValue(is, YamlModuleFile.class));
                }
            }
        }
        return ModuleExpander.resolveExtensions(moduleFiles);
    }

    @SuppressWarnings("unchecked")
    private List<ResolvedInvariant> buildInvariants(Map<String, YamlInvariant> yamlInvariants) {
        List<ResolvedInvariant> invariants = new ArrayList<>();
        for (Map.Entry<String, YamlInvariant> entry : yamlInvariants.entrySet()) {
            invariants.add(YamlInvariantConverter.toDeclarativeInvariant(entry.getKey(), entry.getValue()));
        }
        return invariants;
    }

    private GraphDescriptor toGraphDescriptor(YamlGraph yamlGraph, Map<String, String> typeRegistry) {
        List<NodeDescriptor> nodes = new ArrayList<>();
        List<DependencyDescriptor> deps = new ArrayList<>();

        for (Map.Entry<String, YamlNode> entry : yamlGraph.nodes().entrySet()) {
            String nodeId = entry.getKey();
            YamlNode yamlNode = entry.getValue();
            String specClassName = typeRegistry.get(yamlNode.type());

            nodes.add(new NodeDescriptor.InlineNode(
                    nodeId, specClassName,
                    yamlNode.spec() != null ? yamlNode.spec() : Map.of(),
                    yamlNode.humanGating()));

            for (String dep : yamlNode.dependencyNodeIds()) {
                deps.add(new DependencyDescriptor(nodeId, dep));
            }
        }

        return new GraphDescriptor(
                yamlGraph.desiredState().namespace(),
                yamlGraph.desiredState().name(),
                null, null, nodes, deps,
                List.of(), null, List.of(), List.of());
    }

    private IndexView loadCompositeJandexIndex() throws IOException {
        List<IndexView> indexes = new ArrayList<>();
        Enumeration<URL> resources = Thread.currentThread()
                .getContextClassLoader()
                .getResources("META-INF/jandex.idx");
        while (resources.hasMoreElements()) {
            try (InputStream is = resources.nextElement().openStream()) {
                indexes.add(new IndexReader(is).read());
            }
        }
        return indexes.isEmpty() ? Index.of(new Class<?>[0]) : CompositeIndex.create(indexes);
    }
}
