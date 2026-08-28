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
                            BuildProducer<DesiredStateGraphBuildItem> graphItems,
                            List<io.casehub.desiredstate.annotations.deployment.AdditionalRulesBuildItem> additionalRuleItems) throws IOException, java.net.URISyntaxException {

        IndexView index = indexBuildItem.getIndex();
        Map<String, String> typeRegistry = scanNodeTypes(index);

        if (typeRegistry.isEmpty()) {
            LOG.debug("No @NodeTypeId annotations found — skipping YAML graph discovery");
            return;
        }

        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        List<NamedYamlGraph> yamlGraphs = discoverYamlFiles(yamlMapper);
        Map<String, io.casehub.desiredstate.yaml.model.YamlModule> availableModules =
                discoverModules(yamlMapper);

        if (yamlGraphs.isEmpty()) {
            LOG.debug("No YAML graph files found at " + YAML_PATH_PREFIX);
            return;
        }

        for (NamedYamlGraph named : yamlGraphs) {
            YamlGraph yamlGraph = named.graph();
            String fileName = named.fileName();

            String ns = yamlGraph.desiredState().namespace();
            String name = yamlGraph.desiredState().name();

            List<io.casehub.desiredstate.annotations.runtime.ResolvedInvariant> invariants =
                    buildInvariants(yamlGraph.invariants());

            @SuppressWarnings("rawtypes")
            RuntimeValue<GoalCompiler> compiler;

            if (!yamlGraph.imports().isEmpty()) {
                validateImports(yamlGraph.imports(), availableModules, typeRegistry, fileName);
            }

            List<io.casehub.desiredstate.annotations.runtime.GraphRuleDescriptor> crossSurfaceRules = List.of();
            List<io.casehub.desiredstate.annotations.runtime.GraphInvariantDescriptor> crossSurfaceInvariants = List.of();
            for (var additional : additionalRuleItems) {
                if (additional.namespace().equals(ns) && additional.name().equals(name)) {
                    crossSurfaceRules = additional.rules();
                    crossSurfaceInvariants = additional.invariants();
                    break;
                }
            }

            if (yamlGraph.lifecycle() != null) {
                validateLifecycle(yamlGraph, typeRegistry, fileName);
                compiler = recorder.createYamlLifecycleGoalCompiler(
                        yamlGraph, typeRegistry,
                        yamlGraph.variables() != null ? yamlGraph.variables() : Map.of(),
                        invariants);
            } else {
                validateYamlGraph(yamlGraph, typeRegistry, fileName);
                GraphDescriptor descriptor = toGraphDescriptor(yamlGraph, typeRegistry);
                compiler = recorder.createYamlGoalCompiler(
                        descriptor, typeRegistry,
                        yamlGraph.variables() != null ? yamlGraph.variables() : Map.of(),
                        invariants, yamlGraph, availableModules,
                        crossSurfaceRules, crossSurfaceInvariants);
            }

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

            // Validate YAML rules
            if (!yamlGraph.rules().isEmpty()) {
                validateRules(yamlGraph.rules(), typeRegistry, fileName);
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

    private Map<String, io.casehub.desiredstate.yaml.model.YamlModule> discoverModules(
            ObjectMapper mapper) throws IOException, java.net.URISyntaxException {
        Map<String, io.casehub.desiredstate.yaml.model.YamlModule> modules = new HashMap<>();
        String prefix = "META-INF/desiredstate/modules/";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        java.util.Enumeration<java.net.URL> resources = cl.getResources(prefix);

        Set<String> seen = new HashSet<>();
        while (resources.hasMoreElements()) {
            java.net.URL dirUrl = resources.nextElement();
            if ("file".equals(dirUrl.getProtocol())) {
                java.io.File dir = new java.io.File(dirUrl.toURI().getPath());
                if (dir.isDirectory()) {
                    java.io.File[] yamlFiles = dir.listFiles((d, name) ->
                            name.endsWith(".yaml") || name.endsWith(".yml"));
                    if (yamlFiles != null) {
                        for (java.io.File f : yamlFiles) {
                            if (seen.add(f.getName())) {
                                try (InputStream is = f.toURI().toURL().openStream()) {
                                    io.casehub.desiredstate.yaml.model.YamlModuleFile moduleFile =
                                            mapper.readValue(is,
                                                    io.casehub.desiredstate.yaml.model.YamlModuleFile.class);
                                    if (moduleFile.hasNestedImports()) {
                                        throw new RuntimeException("Module '" + moduleFile.module().name()
                                                + "' in " + f.getName()
                                                + " contains imports — module nesting exceeds the "
                                                + "2-level cap (D10). Modules cannot import other modules.");
                                    }
                                    io.casehub.desiredstate.yaml.model.YamlModule module =
                                            moduleFile.toModule();
                                    modules.put(module.name(), module);
                                }
                            }
                        }
                    }
                }
            }
        }
        return modules;
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
            for (String dep : entry.getValue().dependencyNodeIds()) {
                if (!nodeIds.contains(dep)) {
                    throw new RuntimeException(fileName + ": Node '" + entry.getKey()
                            + "' depends on '" + dep + "' which is not declared");
                }
            }
        }

        detectCycles(graph.nodes(), fileName);
        validateConditionalDependencies(graph.nodes(), fileName);
        validateForEach(graph.nodes(), graph.iterations(), typeRegistry, fileName);
    }

    private void detectCycles(Map<String, YamlNode> nodes, String fileName) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adjList = new HashMap<>();
        for (String id : nodes.keySet()) {
            inDegree.put(id, 0);
            adjList.put(id, new ArrayList<>());
        }
        for (Map.Entry<String, YamlNode> entry : nodes.entrySet()) {
            for (String dep : entry.getValue().dependencyNodeIds()) {
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

    private void validateConditionalDependencies(Map<String, io.casehub.desiredstate.yaml.model.YamlNode> nodes,
                                                 String fileName) {
        Set<String> conditionalNodes = new HashSet<>();
        for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlNode> entry : nodes.entrySet()) {
            if (entry.getValue().when() != null) {
                conditionalNodes.add(entry.getKey());
            }
        }
        if (conditionalNodes.isEmpty()) {return;}

        for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlNode> entry : nodes.entrySet()) {
            String  nodeId            = entry.getKey();
            var     node              = entry.getValue();
            boolean nodeIsConditional = node.when() != null;

            for (Object dep : node.dependsOn()) {
                String  depId            = io.casehub.desiredstate.yaml.model.YamlNode.dependencyNodeId(dep);
                boolean depIsConditional = conditionalNodes.contains(depId);
                boolean depIsOptional    = io.casehub.desiredstate.yaml.model.YamlNode.isDependencyOptional(dep);

                if (depIsConditional && !nodeIsConditional && !depIsOptional) {
                    throw new RuntimeException(fileName + ": Node '" + nodeId
                                               + "' unconditionally depends on conditional node '" + depId
                                               + "' (has when:). Mark the dependency as optional: "
                                               + "{ node: \"" + depId + "\", optional: true }");
                }
            }
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

    static void validateRule(String ruleName, io.casehub.desiredstate.yaml.model.YamlRule rule,
                             Map<String, String> typeRegistry, String fileName) {
        String ctx = fileName + ": rules." + ruleName;

        if (rule.match().isEmpty()) {
            throw new RuntimeException(ctx + ": at least one 'match' binding is required");
        }

        if (rule.actions().isEmpty()) {
            throw new RuntimeException(ctx + ": at least one action is required");
        }

        java.util.Set<String> validActions = java.util.Set.of(
                "addNode", "removeNode", "updateNode", "addDependency", "removeDependency");

        java.util.Set<String> allBindings = new java.util.LinkedHashSet<>();
        for (String binding : rule.match().keySet()) {
            allBindings.add(binding);
            validatePatternType(rule.match().get(binding).type(), typeRegistry,
                                ctx + ".match." + binding);
        }

        validatePatternSection(rule.directDep(), "directDep", allBindings, typeRegistry, ctx, true);
        validatePatternSection(rule.reaches(), "reaches", allBindings, typeRegistry, ctx, true);
        validatePatternSection(rule.notExists(), "notExists", allBindings, typeRegistry, ctx, false);

        for (int i = 0; i < rule.actions().size(); i++) {
            Map<String, Object> action = rule.actions().get(i);
            if (action.size() != 1) {
                throw new RuntimeException(ctx + ".actions[" + i
                                           + "]: each action must have exactly one key (the action type)");
            }
            String actionType = action.keySet().iterator().next();
            if (!validActions.contains(actionType)) {
                throw new RuntimeException(ctx + ".actions[" + i + "]: unknown action type '"
                                           + actionType + "'. Valid: " + validActions);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) action.get(actionType);
            if ("addNode".equals(actionType) || "updateNode".equals(actionType)) {
                if (!params.containsKey("type")) {
                    throw new RuntimeException(ctx + ".actions[" + i
                                               + "." + actionType + "]: 'type' is required");
                }
                Object typeVal = params.get("type");
                if (typeVal instanceof String typeStr
                    && !typeStr.contains("${")
                    && !typeRegistry.containsKey(typeStr)) {
                    throw new RuntimeException(ctx + ".actions[" + i + "." + actionType
                                               + "]: unknown type '" + typeStr + "'. Available: " + typeRegistry.keySet());
                }
            }
        }
    }

    private void validateRules(Map<String, io.casehub.desiredstate.yaml.model.YamlRule> rules,
                               Map<String, String> typeRegistry, String fileName) {
        for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlRule> entry : rules.entrySet()) {
            validateRule(entry.getKey(), entry.getValue(), typeRegistry, fileName);
        }
    }

    static void validateLifecycle(io.casehub.desiredstate.yaml.model.YamlGraph graph,
                                  Map<String, String> typeRegistry, String fileName) {
        if (graph.lifecycle() == null) {return;}

        if (!graph.nodes().isEmpty()) {
            throw new RuntimeException(fileName
                                       + ": cannot have both top-level 'nodes' and 'lifecycle'. "
                                       + "When lifecycle is present, nodes live inside phases.");
        }

        if (!graph.imports().isEmpty()) {
            throw new RuntimeException(fileName
                                       + ": module imports are not yet supported with lifecycle phases. "
                                       + "Use module imports with single-graph mode, "
                                       + "or inline the module nodes into the appropriate phase.");
        }

        List<io.casehub.desiredstate.yaml.model.YamlPhase> phases = graph.lifecycle().phases();
        if (phases.isEmpty()) {
            throw new RuntimeException(fileName
                                       + ": lifecycle must have at least one phase");
        }

        Set<String> phaseIds = new HashSet<>();
        for (int i = 0; i < phases.size(); i++) {
            io.casehub.desiredstate.yaml.model.YamlPhase phase = phases.get(i);
            String                                       ctx   = fileName + ": lifecycle.phases[" + i + "]";

            if (phase.id() == null || phase.id().isBlank()) {
                throw new RuntimeException(ctx + ": phase id is required");
            }
            if (!phaseIds.add(phase.id())) {
                throw new RuntimeException(ctx + ": duplicate phase id '" + phase.id() + "'");
            }

            validateCompletionCondition(phase.completionCondition(), ctx);

            for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlNode> nodeEntry :
                    phase.nodes().entrySet()) {
                String                                      nodeId = nodeEntry.getKey();
                io.casehub.desiredstate.yaml.model.YamlNode node   = nodeEntry.getValue();

                if (!typeRegistry.containsKey(node.type())) {
                    throw new RuntimeException(ctx + ": unknown node type '"
                                               + node.type() + "' for node '" + nodeId + "'");
                }
            }

            validateForEach(phase.nodes(), graph.iterations(), typeRegistry, ctx);
        }

        io.casehub.desiredstate.yaml.model.YamlPhase lastPhase = phases.get(phases.size() - 1);
        if ("allPresent".equals(lastPhase.completionCondition())) {
            LOG.warnf("%s: last phase '%s' uses completionCondition 'allPresent' — "
                      + "the lifecycle will terminate and reconciliation will stop. "
                      + "Use 'never' for steady-state operation.", fileName, lastPhase.id());
        }
    }

    private static void validateCompletionCondition(Object condition, String ctx) {
        if (condition == null) {
            throw new RuntimeException(ctx + ": completionCondition is required");
        }
        if (condition instanceof String s) {
            if (!"allPresent".equals(s) && !"never".equals(s)) {
                throw new RuntimeException(ctx + ": unknown completionCondition '"
                                           + s + "'. Valid: allPresent, never, or { bean: \"name\" }");
            }
        } else if (condition instanceof Map<?, ?> m) {
            if (!m.containsKey("bean")) {
                throw new RuntimeException(ctx
                                           + ": completionCondition map must have 'bean' key");
            }
        } else {
            throw new RuntimeException(ctx + ": completionCondition must be a string "
                                       + "(allPresent, never) or a map ({ bean: \"name\" })");
        }
    }


    static void validateImports(List<io.casehub.desiredstate.yaml.model.YamlImport> imports,
                               Map<String, io.casehub.desiredstate.yaml.model.YamlModule> modules,
                               Map<String, String> typeRegistry, String fileName) {
        Set<String> aliases = new HashSet<>();

        for (int i = 0; i < imports.size(); i++) {
            var imp = imports.get(i);
            String ctx = fileName + ": imports[" + i + "]";

            if (!modules.containsKey(imp.module())) {
                throw new RuntimeException(ctx + ": unknown module '" + imp.module()
                        + "'. Available: " + modules.keySet());
            }

            if (imp.as() == null || imp.as().isBlank()) {
                throw new RuntimeException(ctx + ": 'as' alias is required");
            }

            if (imp.as().contains(".")) {
                throw new RuntimeException(ctx + ": alias '" + imp.as()
                        + "' contains the reserved '.' separator");
            }

            if (!aliases.add(imp.as())) {
                throw new RuntimeException(ctx + ": duplicate alias '" + imp.as() + "'");
            }

            var module = modules.get(imp.module());
            for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlModuleParameter> param :
                    module.parameters().entrySet()) {
                if (param.getValue().required()
                        && !imp.parameters().containsKey(param.getKey())) {
                    throw new RuntimeException(ctx + ": required parameter '"
                            + param.getKey() + "' is missing for module '"
                            + imp.module() + "'");
                }
            }
        }
    }

    static void validateForEach(Map<String, YamlNode> nodes,
                               Map<String, io.casehub.desiredstate.yaml.model.YamlIterationGroup> iterations,
                               Map<String, String> typeRegistry, String fileName) {
        for (String nodeId : nodes.keySet()) {
            if (nodeId.contains(".")) {
                throw new RuntimeException(fileName + ": node ID '" + nodeId
                        + "' contains the reserved '.' separator. "
                        + "User-declared node IDs must not contain '.'.");
            }
        }

        Map<String, String> nodeGroupMap = new HashMap<>();
        Set<String> forEachNodeIds = new HashSet<>();

        for (Map.Entry<String, YamlNode> entry : nodes.entrySet()) {
            String nodeId = entry.getKey();
            Object forEach = entry.getValue().forEach();
            if (forEach == null) {
                nodeGroupMap.put(nodeId, null);
            } else if (forEach instanceof String groupRef) {
                if (!iterations.containsKey(groupRef)) {
                    throw new RuntimeException(fileName + ": node '" + nodeId
                            + "' references unknown iteration group '" + groupRef
                            + "'. Available: " + iterations.keySet());
                }
                nodeGroupMap.put(nodeId, groupRef);
                forEachNodeIds.add(nodeId);
            } else if (forEach instanceof Map<?, ?>) {
                nodeGroupMap.put(nodeId, "__inline__" + nodeId);
                forEachNodeIds.add(nodeId);
            } else {
                throw new RuntimeException(fileName + ": node '" + nodeId
                        + "': forEach must be a string (group name) or map ({as, in})");
            }
        }

        for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlIterationGroup> entry :
                iterations.entrySet()) {
            for (Object val : entry.getValue().inAsList()) {
                if (val instanceof String s && s.contains(".") && !s.contains("${")) {
                    throw new RuntimeException(fileName + ": iteration group '"
                            + entry.getKey() + "': value '" + s
                            + "' contains the reserved '.' separator");
                }
            }
        }

        for (Map.Entry<String, YamlNode> entry : nodes.entrySet()) {
            String nodeId = entry.getKey();
            String nodeGroup = nodeGroupMap.get(nodeId);

            for (String depId : entry.getValue().dependencyNodeIds()) {
                if (!nodes.containsKey(depId)) {continue;}
                String depGroup = nodeGroupMap.get(depId);

                if (nodeGroup == null && forEachNodeIds.contains(depId)) {
                    throw new RuntimeException(fileName + ": Node '" + nodeId
                            + "' depends on forEach template '" + depId
                            + "'. Non-forEach nodes cannot depend on forEach templates "
                            + "(the template ID doesn't exist after expansion). "
                            + "Use a forEach node with the same iteration group for fan-in.");
                }

                if (nodeGroup != null && depGroup != null
                        && !nodeGroup.equals(depGroup)) {
                    throw new RuntimeException(fileName + ": Node '" + nodeId
                            + "' (group: " + nodeGroup + ") depends on '" + depId
                            + "' (group: " + depGroup + "). "
                            + "forEach nodes referencing different groups cannot depend on each other. "
                            + "Use the same named group for aligned iteration.");
                }
            }
        }
    }

    private static void validatePatternSection(Map<String, io.casehub.desiredstate.yaml.model.YamlPattern> patterns,
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

    private static void validatePatternType(String type, Map<String, String> typeRegistry, String ctx) {
        if (!"*".equals(type) && !typeRegistry.containsKey(type)) {
            throw new RuntimeException(ctx + ": unknown type '" + type + "'. Available: " + typeRegistry.keySet());
        }
    }

    private List<io.casehub.desiredstate.annotations.runtime.ResolvedInvariant> buildInvariants(
            Map<String, io.casehub.desiredstate.yaml.model.YamlInvariant> yamlInvariants) {
        List<io.casehub.desiredstate.annotations.runtime.ResolvedInvariant> invariants = new ArrayList<>();
        for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlInvariant> entry : yamlInvariants.entrySet()) {
            invariants.add(io.casehub.desiredstate.yaml.YamlInvariantConverter
                                   .toDeclarativeInvariant(entry.getKey(), entry.getValue()));
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

    private record NamedYamlGraph(String fileName, YamlGraph graph) {}
}
