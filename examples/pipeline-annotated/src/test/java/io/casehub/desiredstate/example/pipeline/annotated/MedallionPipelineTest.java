package io.casehub.desiredstate.example.pipeline.annotated;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.annotations.DependsOn;
import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.FaultPolicyDef;
import io.casehub.desiredstate.annotations.Node;
import io.casehub.desiredstate.annotations.Tier;
import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.DesiredStateGraphRecorder;
import io.casehub.desiredstate.annotations.runtime.FaultPolicyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.annotations.runtime.TierDescriptor;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.example.pipeline.DataSourceSpec;
import io.casehub.desiredstate.example.pipeline.PipelineNodeTypes;
import io.casehub.desiredstate.example.pipeline.TransformerSpec;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MedallionPipelineTest {

    public static class MedallionPipelineImpl implements MedallionPipeline {}

    private static GoalCompiler<Void> compiler;
    private static FaultPolicy faultPolicy;
    private static final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @BeforeAll
    static void buildFromAnnotations() {
        GraphDescriptor descriptor = buildDescriptorFromAnnotations();
        DesiredStateGraphRecorder recorder = new DesiredStateGraphRecorder();
        compiler = recorder.createGoalCompiler(descriptor).getValue();

        if (!descriptor.faultPolicies().isEmpty()) {
            faultPolicy = recorder.createFaultPolicy(
                    descriptor.faultPolicies().get(0), descriptor.implClassName()).getValue();
        }
    }

    @Test
    void compilesAnnotatedPipelineToGraph() {
        CompilationResult result = compiler.compile(null, factory);
        assertThat(result).isInstanceOf(CompilationResult.SingleGraph.class);

        DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();
        assertThat(graph.nodes()).hasSize(8);
    }

    @Test
    void bronzeLayerNodes() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.nodes().get(NodeId.of("csv-source")).type())
                .isEqualTo(PipelineNodeTypes.DATA_SOURCE);
        assertThat(graph.nodes().get(NodeId.of("csv-source")).spec())
                .isInstanceOf(DataSourceSpec.class);
        assertThat(graph.nodes().get(NodeId.of("customer-schema")).type())
                .isEqualTo(PipelineNodeTypes.SCHEMA);
        assertThat(graph.nodes().get(NodeId.of("csv-ingest")).type())
                .isEqualTo(PipelineNodeTypes.INGESTION);
    }

    @Test
    void bronzeDependencyChain() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.dependencies())
                .contains(new Dependency(NodeId.of("csv-ingest"), NodeId.of("csv-source")));
    }

    @Test
    void silverLayerDependencies() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.dependenciesOf(NodeId.of("dedup-cleanser")))
                .contains(NodeId.of("csv-ingest"), NodeId.of("customer-schema"));
        assertThat(graph.dependenciesOf(NodeId.of("geo-enricher")))
                .contains(NodeId.of("dedup-cleanser"));
        assertThat(graph.dependenciesOf(NodeId.of("quality-validator")))
                .contains(NodeId.of("geo-enricher"), NodeId.of("customer-schema"));
    }

    @Test
    void goldLayerHumanGating() {
        DesiredStateGraph graph = compileSingleGraph();
        DesiredNode txNode = graph.nodes().get(NodeId.of("aggregate-tx"));
        assertThat(txNode.humanGating()).isEqualTo(HumanGating.PROVISION_ONLY);
        assertThat(txNode.type()).isEqualTo(PipelineNodeTypes.TRANSFORMER);
        assertThat(txNode.spec()).isInstanceOf(TransformerSpec.class);

        DesiredNode sinkNode = graph.nodes().get(NodeId.of("warehouse-sink"));
        assertThat(sinkNode.humanGating()).isEqualTo(HumanGating.PROVISION_ONLY);
        assertThat(sinkNode.type()).isEqualTo(PipelineNodeTypes.SINK);
    }

    @Test
    void goldDependencyChain() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.dependenciesOf(NodeId.of("aggregate-tx")))
                .contains(NodeId.of("quality-validator"));
        assertThat(graph.dependenciesOf(NodeId.of("warehouse-sink")))
                .contains(NodeId.of("aggregate-tx"));
    }

    @Test
    void faultPolicyExists() {
        assertThat(faultPolicy).isNotNull();
    }

    @Test
    void faultPolicyEscalatesOnThreshold() {
        DesiredStateGraph graph = compileSingleGraph();

        var faultEvent = new FaultEvent(NodeId.of("aggregate-tx"),
                FaultType.PROVISION_FAILED, "connection timeout");

        for (int i = 0; i < 2; i++) {
            assertThat(faultPolicy.onFault("tenant-1", faultEvent, graph, null)).isEmpty();
        }

        var mutations = faultPolicy.onFault("tenant-1", faultEvent, graph, null);
        assertThat(mutations).isNotEmpty();
    }

    private DesiredStateGraph compileSingleGraph() {
        return ((CompilationResult.SingleGraph) compiler.compile(null, factory)).graph();
    }

    private static GraphDescriptor buildDescriptorFromAnnotations() {
        Class<?> iface = MedallionPipeline.class;
        DesiredState ds = iface.getAnnotation(DesiredState.class);

        List<NodeDescriptor> nodes = new ArrayList<>();
        List<DependencyDescriptor> deps = new ArrayList<>();

        for (Method method : iface.getDeclaredMethods()) {
            Node nodeAnn = method.getAnnotation(Node.class);
            if (nodeAnn != null) {
                nodes.add(new NodeDescriptor.InterfaceNode(nodeAnn.value(), method.getName(),
                        method.getReturnType().getName(), nodeAnn.humanGating()));

                DependsOn dependsOn = method.getAnnotation(DependsOn.class);
                if (dependsOn != null) {
                    for (String dep : dependsOn.value()) {
                        deps.add(new DependencyDescriptor(nodeAnn.value(), dep));
                    }
                }
            }
        }

        List<FaultPolicyDescriptor> faultPolicies = new ArrayList<>();
        for (FaultPolicyDef fpAnn : iface.getAnnotationsByType(FaultPolicyDef.class)) {
            List<TierDescriptor> tiers = new ArrayList<>();
            for (Tier tier : fpAnn.tiers()) {
                tiers.add(new TierDescriptor(tier.threshold(), tier.review()));
            }
            faultPolicies.add(new FaultPolicyDescriptor(
                    Arrays.asList(fpAnn.faultTypes()),
                    Arrays.asList(fpAnn.nodeTypes()),
                    Arrays.asList(fpAnn.ignoreTypes()),
                    fpAnn.namespace(),
                    tiers, null));
        }

        return new GraphDescriptor(ds.namespace(), ds.name(),
                iface.getName(), MedallionPipelineImpl.class.getName(),
                nodes, deps, faultPolicies, null);
    }
}
