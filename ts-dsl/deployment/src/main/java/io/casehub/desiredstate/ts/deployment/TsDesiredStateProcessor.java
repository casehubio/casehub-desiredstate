package io.casehub.desiredstate.ts.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.annotations.deployment.AdditionalRulesBuildItem;
import io.casehub.desiredstate.annotations.deployment.DesiredStateGraphBuildItem;
import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphInvariantDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphRuleDescriptor;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.annotations.runtime.ResolvedInvariant;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.ts.TsEnvelope;
import io.casehub.desiredstate.ts.TsEnvelopeNode;
import io.casehub.desiredstate.ts.TsEnvelopePhase;
import io.casehub.desiredstate.ts.TsGraphRecorder;
import io.casehub.desiredstate.ts.TsLifecycleEnvelope;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.runtime.RuntimeValue;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.quarkus.deployment.annotations.ExecutionTime.RUNTIME_INIT;

public class TsDesiredStateProcessor {

    private static final Logger LOG = Logger.getLogger(TsDesiredStateProcessor.class);
    private static final String DS_PATH_PREFIX = "META-INF/desiredstate/";
    private static final DotName NODE_SPEC = DotName.createSimple(NodeSpec.class.getName());
    private static final DotName NODE_TYPE_ID = DotName.createSimple(NodeTypeId.class.getName());

    @BuildStep
    @Record(RUNTIME_INIT)
    void discoverTsGraphs(CombinedIndexBuildItem indexBuildItem,
                          TsGraphRecorder recorder,
                          BuildProducer<SyntheticBeanBuildItem> syntheticBeans,
                          BuildProducer<DesiredStateGraphBuildItem> graphItems,
                          List<AdditionalRulesBuildItem> additionalRuleItems) throws IOException, java.net.URISyntaxException {

        IndexView index = indexBuildItem.getIndex();
        Map<String, String> typeRegistry = scanNodeTypes(index);

        if (typeRegistry.isEmpty()) {
            LOG.debug("No @NodeTypeId annotations found — skipping TS graph discovery");
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        List<NamedEnvelope> envelopes = discoverJsonFiles(mapper);

        if (envelopes.isEmpty()) {
            LOG.debug("No .ds.json files found at " + DS_PATH_PREFIX);
            return;
        }

        for (NamedEnvelope named : envelopes) {
            String fileName = named.fileName;
            String ns;
            String name;

            @SuppressWarnings("rawtypes")
            RuntimeValue<GoalCompiler> compiler;

            if ("lifecycle".equals(named.kind)) {
                TsLifecycleEnvelope lifecycle = mapper.readValue(named.json, TsLifecycleEnvelope.class);
                ns = lifecycle.namespace();
                name = lifecycle.name();

                validatePhaseNodes(lifecycle.phases(), typeRegistry, fileName);

                compiler = recorder.createTsLifecycleGoalCompiler(
                        lifecycle, typeRegistry, List.of());
            } else {
                TsEnvelope envelope = mapper.readValue(named.json, TsEnvelope.class);
                ns = envelope.namespace();
                name = envelope.name();

                validateNodes(envelope.nodes(), typeRegistry, fileName);
                validateDependencies(envelope.nodes(), envelope.dependencies(), fileName);
                detectCycles(envelope.nodes(), envelope.dependencies(), fileName);

                GraphDescriptor descriptor = toGraphDescriptor(envelope, typeRegistry);

                List<GraphRuleDescriptor> crossSurfaceRules = List.of();
                List<GraphInvariantDescriptor> crossSurfaceInvariants = List.of();
                for (var additional : additionalRuleItems) {
                    if (additional.namespace().equals(ns) && additional.name().equals(name)) {
                        crossSurfaceRules = additional.rules();
                        crossSurfaceInvariants = additional.invariants();
                        break;
                    }
                }

                compiler = recorder.createTsGoalCompiler(
                        descriptor, typeRegistry, List.of(),
                        crossSurfaceRules, crossSurfaceInvariants);
            }

            syntheticBeans.produce(SyntheticBeanBuildItem.configure(GoalCompiler.class)
                    .scope(ApplicationScoped.class)
                    .unremovable()
                    .setRuntimeInit()
                    .addQualifier()
                        .annotation(io.casehub.desiredstate.annotations.DesiredStateQualifier.class)
                        .addValue("namespace", ns)
                        .addValue("name", name)
                        .done()
                    .runtimeValue(compiler)
                    .done());

            graphItems.produce(new DesiredStateGraphBuildItem(ns, name, "ts:" + fileName));
        }
    }

    private Map<String, String> scanNodeTypes(IndexView index) {
        Map<String, String> typeMap = new HashMap<>();
        for (AnnotationInstance ann : index.getAnnotations(NODE_TYPE_ID)) {
            String typeId = ann.value().asString();
            String className = ann.target().asClass().name().toString();
            typeMap.put(typeId, className);
        }
        return typeMap;
    }

    private List<NamedEnvelope> discoverJsonFiles(ObjectMapper mapper) throws IOException, java.net.URISyntaxException {
        List<NamedEnvelope> result = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> resources = cl.getResources(DS_PATH_PREFIX);

        while (resources.hasMoreElements()) {
            URL dir = resources.nextElement();
            if ("file".equals(dir.getProtocol())) {
                java.io.File dirFile = new java.io.File(dir.toURI().getPath());
                if (dirFile.isDirectory()) {
                    for (java.io.File f : dirFile.listFiles()) {
                        if (f.getName().endsWith(".ds.json")) {
                            String json = java.nio.file.Files.readString(f.toPath());
                            String kind = readKind(mapper, json, f.getName());
                            result.add(new NamedEnvelope(
                                    f.getName().replace(".ds.json", ""), json, kind));
                        }
                    }
                }
            } else if ("jar".equals(dir.getProtocol())) {
                JarURLConnection conn = (JarURLConnection) dir.openConnection();
                java.util.jar.JarFile jar = conn.getJarFile();
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.getName().startsWith(DS_PATH_PREFIX) && entry.getName().endsWith(".ds.json")) {
                        try (InputStream is = jar.getInputStream(entry)) {
                            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                            String name = entry.getName().substring(DS_PATH_PREFIX.length())
                                    .replace(".ds.json", "");
                            String kind = readKind(mapper, json, name);
                            result.add(new NamedEnvelope(name, json, kind));
                        }
                    }
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private String readKind(ObjectMapper mapper, String json, String context) throws IOException {
        Map<String, Object> raw = mapper.readValue(json, Map.class);
        String kind = (String) raw.get("kind");
        if (kind == null || (!kind.equals("single") && !kind.equals("lifecycle"))) {
            throw new IllegalStateException("TS envelope '" + context
                    + "' must have \"kind\": \"single\" or \"lifecycle\". Got: " + kind);
        }
        return kind;
    }

    private void validateNodes(List<TsEnvelopeNode> nodes, Map<String, String> typeRegistry,
                               String fileName) {
        for (TsEnvelopeNode node : nodes) {
            if (!typeRegistry.containsKey(node.type())) {
                throw new IllegalStateException("TS graph '" + fileName + "': unknown node type '"
                        + node.type() + "' on node '" + node.id()
                        + "'. Available: " + typeRegistry.keySet());
            }
        }
    }

    private void validatePhaseNodes(List<TsEnvelopePhase> phases, Map<String, String> typeRegistry,
                                     String fileName) {
        Set<String> phaseIds = new HashSet<>();
        for (TsEnvelopePhase phase : phases) {
            if (!phaseIds.add(phase.id())) {
                throw new IllegalStateException("TS lifecycle '" + fileName
                        + "': duplicate phase ID '" + phase.id() + "'");
            }
            validateNodes(phase.nodes(), typeRegistry, fileName + " phase:" + phase.id());
        }
    }

    private void validateDependencies(List<TsEnvelopeNode> nodes,
                                       List<DependencyDescriptor> deps, String fileName) {
        Set<String> nodeIds = new HashSet<>();
        for (TsEnvelopeNode node : nodes) { nodeIds.add(node.id()); }

        for (DependencyDescriptor dep : deps) {
            if (!nodeIds.contains(dep.from())) {
                throw new IllegalStateException("TS graph '" + fileName
                        + "': dependency from unknown node '" + dep.from() + "'");
            }
            if (!nodeIds.contains(dep.to())) {
                throw new IllegalStateException("TS graph '" + fileName
                        + "': dependency to unknown node '" + dep.to() + "'");
            }
        }
    }

    private void detectCycles(List<TsEnvelopeNode> nodes, List<DependencyDescriptor> deps,
                               String fileName) {
        Map<String, Set<String>> adj = new HashMap<>();
        for (TsEnvelopeNode node : nodes) { adj.put(node.id(), new HashSet<>()); }
        for (DependencyDescriptor dep : deps) { adj.get(dep.from()).add(dep.to()); }

        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (String nodeId : adj.keySet()) {
            if (hasCycle(nodeId, adj, visited, inStack)) {
                throw new IllegalStateException("TS graph '" + fileName
                        + "': dependency cycle detected involving node '" + nodeId + "'");
            }
        }
    }

    private boolean hasCycle(String node, Map<String, Set<String>> adj,
                              Set<String> visited, Set<String> inStack) {
        if (inStack.contains(node)) return true;
        if (visited.contains(node)) return false;
        visited.add(node);
        inStack.add(node);
        for (String dep : adj.getOrDefault(node, Set.of())) {
            if (hasCycle(dep, adj, visited, inStack)) return true;
        }
        inStack.remove(node);
        return false;
    }

    private GraphDescriptor toGraphDescriptor(TsEnvelope envelope, Map<String, String> typeRegistry) {
        List<NodeDescriptor> nodes = new ArrayList<>();
        for (TsEnvelopeNode en : envelope.nodes()) {
            nodes.add(new NodeDescriptor.InlineNode(
                    en.id(), typeRegistry.get(en.type()), en.spec(), en.humanGating()));
        }
        return new GraphDescriptor(
                envelope.namespace(), envelope.name(), null, null,
                nodes, envelope.dependencies(),
                List.of(), null, List.of(), List.of());
    }

    private record NamedEnvelope(String fileName, String json, String kind) {}
}
