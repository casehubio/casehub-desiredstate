package io.casehub.desiredstate.yaml.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.annotations.DesiredStateQualifier;
import io.casehub.desiredstate.annotations.deployment.DesiredStateGraphBuildItem;
import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.yaml.YamlGraphRecorder;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import io.casehub.desiredstate.yaml.model.YamlNode;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.runtime.RuntimeValue;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class YamlDesiredStateProcessor {

    private static final Logger LOG = Logger.getLogger(YamlDesiredStateProcessor.class);
    private static final String YAML_PATH_PREFIX = "META-INF/desiredstate/";
    private static final DotName NODE_SPEC = DotName.createSimple(NodeSpec.class.getName());
    private static final DotName NODE_TYPE_ID = DotName.createSimple(NodeTypeId.class.getName());

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void discoverYamlGraphs(CombinedIndexBuildItem indexBuildItem,
                            YamlGraphRecorder recorder,
                            BuildProducer<SyntheticBeanBuildItem> syntheticBeans,
                            BuildProducer<DesiredStateGraphBuildItem> graphItems) throws IOException, java.net.URISyntaxException {

        IndexView index = indexBuildItem.getIndex();
        Map<String, String> typeRegistry = scanNodeTypes(index);

        if (typeRegistry.isEmpty()) {
            LOG.debug("No @NodeTypeId annotations found — skipping YAML graph discovery");
            return;
        }

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        List<NamedYamlGraph> yamlGraphs = discoverYamlFiles(yamlMapper);

        if (yamlGraphs.isEmpty()) {
            LOG.debug("No YAML graph files found at " + YAML_PATH_PREFIX);
            return;
        }

        for (NamedYamlGraph named : yamlGraphs) {
            YamlGraph yamlGraph = named.graph();
            String fileName = named.fileName();

            validateYamlGraph(yamlGraph, typeRegistry, fileName);

            GraphDescriptor descriptor = toGraphDescriptor(yamlGraph, typeRegistry);

            @SuppressWarnings("rawtypes")
            RuntimeValue<GoalCompiler> compiler = recorder.createYamlGoalCompiler(
                    descriptor, typeRegistry,
                    yamlGraph.variables() != null ? yamlGraph.variables() : Map.of());

            String ns = yamlGraph.desiredState().namespace();
            String name = yamlGraph.desiredState().name();

            syntheticBeans.produce(SyntheticBeanBuildItem.configure(GoalCompiler.class)
                    .scope(ApplicationScoped.class)
                    .unremovable()
                    .setRuntimeInit()
                    .addQualifier()
                        .annotation(DesiredStateQualifier.class)
                        .addValue("namespace", ns)
                        .addValue("name", name)
                        .done()
                    .runtimeValue(compiler)
                    .done());

            graphItems.produce(new DesiredStateGraphBuildItem(ns, name, "yaml:" + fileName));

            // Validate YAML invariants
            if (!yamlGraph.invariants().isEmpty()) {
                validateInvariants(yamlGraph.invariants(), typeRegistry, fileName);
            }

            // Register YAML fault policies as ThresholdFaultPolicy beans
            if (!yamlGraph.faultPolicy().isEmpty()) {
                validateFaultPolicies(yamlGraph.faultPolicy(), typeRegistry, fileName);
                for (int i = 0; i < yamlGraph.faultPolicy().size(); i++) {
                    var yamlPolicy = yamlGraph.faultPolicy().get(i);
                    RuntimeValue<io.casehub.desiredstate.api.ThresholdFaultPolicy> faultPolicy =
                            recorder.createYamlFaultPolicy(yamlPolicy, typeRegistry);

                    syntheticBeans.produce(SyntheticBeanBuildItem
                            .configure(io.casehub.desiredstate.api.FaultPolicy.class)
                            .scope(ApplicationScoped.class)
                            .unremovable()
                            .setRuntimeInit()
                            .addQualifier()
                                .annotation(DesiredStateQualifier.class)
                                .addValue("namespace", ns)
                                .addValue("name", yamlPolicy.namespace())
                                .done()
                            .runtimeValue(faultPolicy)
                            .done());
                }
            }
        }
    }

    @BuildStep
    @Produce(ServiceStartBuildItem.class)
    void validateNoDuplicateGraphs(List<DesiredStateGraphBuildItem> graphs) {
        Map<String, List<String>> byQualifiedName = new HashMap<>();
        for (DesiredStateGraphBuildItem item : graphs) {
            byQualifiedName.computeIfAbsent(item.qualifiedName(), k -> new ArrayList<>())
                    .add(item.source());
        }
        for (Map.Entry<String, List<String>> entry : byQualifiedName.entrySet()) {
            if (entry.getValue().size() > 1) {
                throw new RuntimeException("Graph '" + entry.getKey()
                        + "' declared by multiple sources: " + entry.getValue());
            }
        }
    }

    private Map<String, String> scanNodeTypes(IndexView index) {
        Map<String, String> registry = new HashMap<>();
        for (AnnotationInstance ann : index.getAnnotations(NODE_TYPE_ID)) {
            if (ann.target().kind() == org.jboss.jandex.AnnotationTarget.Kind.CLASS) {
                ClassInfo cls = ann.target().asClass();
                if (index.getAllKnownImplementors(NODE_SPEC).contains(cls)) {
                    String typeId = ann.value().asString();
                    String existing = registry.put(typeId, cls.name().toString());
                    if (existing != null) {
                        throw new RuntimeException("NodeType '" + typeId
                                + "' claimed by both " + existing + " and " + cls.name());
                    }
                }
            }
        }
        return registry;
    }

    private List<NamedYamlGraph> discoverYamlFiles(ObjectMapper mapper) throws IOException, java.net.URISyntaxException {
        List<NamedYamlGraph> graphs = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> resources = cl.getResources(YAML_PATH_PREFIX);

        Set<String> seen = new HashSet<>();
        while (resources.hasMoreElements()) {
            URL dirUrl = resources.nextElement();
            if ("file".equals(dirUrl.getProtocol())) {
                java.io.File dir = new java.io.File(dirUrl.toURI().getPath());
                if (dir.isDirectory()) {
                    java.io.File[] yamlFiles = dir.listFiles((d, name) ->
                            name.endsWith(".yaml") || name.endsWith(".yml"));
                    if (yamlFiles != null) {
                        for (java.io.File f : yamlFiles) {
                            if (seen.add(f.getName())) {
                                try (InputStream is = f.toURI().toURL().openStream()) {
                                    graphs.add(new NamedYamlGraph(f.getName(), mapper.readValue(is, YamlGraph.class)));
                                }
                            }
                        }
                    }
                }
            } else if ("jar".equals(dirUrl.getProtocol())) {
                String jarPath = dirUrl.getPath();
                String prefix = YAML_PATH_PREFIX;
                try (java.util.jar.JarInputStream jis = new java.util.jar.JarInputStream(
                        new URL(jarPath.substring(0, jarPath.indexOf("!"))).openStream())) {
                    java.util.jar.JarEntry entry;
                    while ((entry = jis.getNextJarEntry()) != null) {
                        String name = entry.getName();
                        if (name.startsWith(prefix) && !name.equals(prefix)
                                && (name.endsWith(".yaml") || name.endsWith(".yml"))
                                && seen.add(name)) {
                            URL yamlUrl = cl.getResource(name);
                            if (yamlUrl != null) {
                                try (InputStream is = yamlUrl.openStream()) {
                                    graphs.add(new NamedYamlGraph(
                                            name.substring(prefix.length()),
                                            mapper.readValue(is, YamlGraph.class)));
                                }
                            }
                        }
                    }
                }
            }
        }
        return graphs;
    }

    private void validateYamlGraph(YamlGraph graph, Map<String, String> typeRegistry, String fileName) {
        if (graph.desiredState() == null || graph.desiredState().namespace() == null
                || graph.desiredState().name() == null) {
            throw new RuntimeException(fileName + ": desiredState.namespace and desiredState.name are required");
        }

        Set<String> nodeIds = new HashSet<>();
        for (Map.Entry<String, YamlNode> entry : graph.nodes().entrySet()) {
            String nodeId = entry.getKey();
            YamlNode node = entry.getValue();

            if (!nodeIds.add(nodeId)) {
                throw new RuntimeException(fileName + ": Duplicate node ID '" + nodeId + "'");
            }
            if (!typeRegistry.containsKey(node.type())) {
                throw new RuntimeException(fileName + ": Unknown node type '" + node.type()
                        + "' for node '" + nodeId + "'. Available: " + typeRegistry.keySet());
            }
        }

        for (Map.Entry<String, YamlNode> entry : graph.nodes().entrySet()) {
            for (String dep : entry.getValue().dependsOn()) {
                if (!nodeIds.contains(dep)) {
                    throw new RuntimeException(fileName + ": Node '" + entry.getKey()
                            + "' depends on '" + dep + "' which is not declared");
                }
            }
        }

        detectCycles(graph.nodes(), fileName);
    }

    private void detectCycles(Map<String, YamlNode> nodes, String fileName) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adjList = new HashMap<>();
        for (String id : nodes.keySet()) {
            inDegree.put(id, 0);
            adjList.put(id, new ArrayList<>());
        }
        for (Map.Entry<String, YamlNode> entry : nodes.entrySet()) {
            for (String dep : entry.getValue().dependsOn()) {
                adjList.get(dep).add(entry.getKey());
                inDegree.merge(entry.getKey(), 1, Integer::sum);
            }
        }

        List<String> queue = new ArrayList<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }

        int processed = 0;
        int idx = 0;
        while (idx < queue.size()) {
            String node = queue.get(idx++);
            processed++;
            for (String dependent : adjList.get(node)) {
                if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
                    queue.add(dependent);
                }
            }
        }

        if (processed < nodes.size()) {
            Set<String> cyclic = new HashSet<>(nodes.keySet());
            cyclic.removeAll(new HashSet<>(queue));
            throw new RuntimeException(fileName
                    + ": Cyclic dependency detected involving nodes: " + cyclic);
        }
    }

    private void validateFaultPolicies(List<io.casehub.desiredstate.yaml.model.YamlFaultPolicy> policies,
                                       Map<String, String> typeRegistry, String fileName) {
        for (int i = 0; i < policies.size(); i++) {
            var    policy = policies.get(i);
            String ctx    = fileName + ": faultPolicy[" + i + "]";

            if (policy.faultTypes().isEmpty()) {
                throw new RuntimeException(ctx + ": faultTypes must not be empty");
            }

            if (policy.tiers().isEmpty()) {
                throw new RuntimeException(ctx + ": at least one tier is required");
            }

            if (policy.namespace() == null || policy.namespace().isBlank()) {
                throw new RuntimeException(ctx + ": namespace is required");
            }

            int prevThreshold = 0;
            for (int t = 0; t < policy.tiers().size(); t++) {
                var    tier    = policy.tiers().get(t);
                String tierCtx = ctx + ".tiers[" + t + "]";

                if (tier.threshold() < 1) {
                    throw new RuntimeException(tierCtx + ": threshold must be >= 1, got " + tier.threshold());
                }
                if (tier.threshold() <= prevThreshold) {
                    throw new RuntimeException(tierCtx + ": threshold " + tier.threshold()
                                               + " must be greater than previous threshold " + prevThreshold);
                }
                prevThreshold = tier.threshold();

                if (tier.reviewNode() == null || tier.reviewNode().type() == null) {
                    throw new RuntimeException(tierCtx + ": reviewNode.type is required");
                }
                if (!typeRegistry.containsKey(tier.reviewNode().type())) {
                    throw new RuntimeException(tierCtx + ": unknown reviewNode type '"
                                               + tier.reviewNode().type() + "'. Available: " + typeRegistry.keySet());
                }
            }
        }
    }

    private void validateInvariants(Map<String, io.casehub.desiredstate.yaml.model.YamlInvariant> invariants,
                                    Map<String, String> typeRegistry, String fileName) {
        for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlInvariant> entry : invariants.entrySet()) {
            String invName = entry.getKey();
            var    inv     = entry.getValue();
            String ctx     = fileName + ": invariants." + invName;

            if (inv.match().isEmpty()) {
                throw new RuntimeException(ctx + ": at least one 'match' binding is required");
            }

            Set<String> allBindings = new java.util.LinkedHashSet<>();
            for (String binding : inv.match().keySet()) {
                allBindings.add(binding);
                validatePatternType(inv.match().get(binding).type(), typeRegistry, ctx + ".match." + binding);
            }

            validatePatternSection(inv.directDep(), "directDep", allBindings, typeRegistry, ctx, true);
            validatePatternSection(inv.reaches(), "reaches", allBindings, typeRegistry, ctx, true);
            validatePatternSection(inv.notExists(), "notExists", allBindings, typeRegistry, ctx, false);
        }
    }

    private void validatePatternSection(Map<String, io.casehub.desiredstate.yaml.model.YamlPattern> patterns,
                                        String sectionName, Set<String> allBindings,
                                        Map<String, String> typeRegistry, String ctx, boolean addsBinding) {
        for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlPattern> entry : patterns.entrySet()) {
            String binding    = entry.getKey();
            var    pattern    = entry.getValue();
            String patternCtx = ctx + "." + sectionName + "." + binding;

            if (pattern.of() != null && !pattern.of().isEmpty() && !allBindings.contains(pattern.of())) {
                throw new RuntimeException(patternCtx + ": 'of' references unknown binding '"
                                           + pattern.of() + "'. Available: " + allBindings);
            }

            validatePatternType(pattern.type(), typeRegistry, patternCtx);

            if (addsBinding) {
                allBindings.add(binding);
            }
        }
    }

    private void validatePatternType(String type, Map<String, String> typeRegistry, String ctx) {
        if (!"*".equals(type) && !typeRegistry.containsKey(type)) {
            throw new RuntimeException(ctx + ": unknown type '" + type + "'. Available: " + typeRegistry.keySet());
        }
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

            for (String dep : yamlNode.dependsOn()) {
                deps.add(new DependencyDescriptor(nodeId, dep));
            }
        }

        return new GraphDescriptor(
                yamlGraph.desiredState().namespace(),
                yamlGraph.desiredState().name(),
                null, null, nodes, deps,
                List.of(), null, List.of(), List.of());
    }

    private record NamedYamlGraph(String fileName, YamlGraph graph) {}
}
