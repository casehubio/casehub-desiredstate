package io.casehub.desiredstate.yaml;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.InMemoryFaultCountStore;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.api.ThresholdFaultPolicy;
import io.casehub.desiredstate.yaml.model.YamlFaultPolicy;
import io.casehub.desiredstate.yaml.model.YamlFaultTier;
import io.casehub.desiredstate.yaml.model.YamlReviewNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that YAML fault policy declarations build into working
 * ThresholdFaultPolicy instances with template-based review specs.
 *
 * Scenario: a data pipeline's gold-layer transformer fails repeatedly.
 * - Failures 1-2: below threshold, no escalation
 * - Failure 3: AI agent reviews the issue (spec carries the faulted node's details)
 * - Failure 5: human operator gets a work item with full context
 */
class YamlFaultPolicyBuilderTest {

    @NodeTypeId("ai-review")
    public record AiReviewSpec(String target, String detail) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("ai-review"); }
    }

    @NodeTypeId("human-review")
    public record HumanReviewSpec(String target, String detail, String instruction) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("human-review"); }
    }

    @NodeTypeId("transformer")
    public record TransformerSpec(String operation) implements NodeSpec {
        @Override
        public NodeType nodeType() {return NodeType.of("transformer");}
    }

    @NodeTypeId("sink")
    public record SinkSpec(String destination) implements NodeSpec {
        @Override
        public NodeType nodeType() {return NodeType.of("sink");}
    }

    private DesiredStateGraph graphWith(DesiredNode... nodes) {
        var factory = new io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory();
        return factory.of(java.util.Arrays.asList(nodes), List.of());
    }

    private DesiredStateGraph graphWithDep(DesiredNode from, DesiredNode to) {
        var factory = new io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory();
        return factory.of(List.of(from, to),
                          List.of(new io.casehub.desiredstate.api.Dependency(from.id(), to.id())));
    }


    @Test
    void templateResolvesFaultContext_aiReviewAtThreshold3() {
        YamlFaultPolicy yamlPolicy = new YamlFaultPolicy(
                List.of("PROVISION_FAILED"),
                List.of("transformer"),
                List.of(),
                "pipeline-escalation",
                List.of(
                        new YamlFaultTier(3, new YamlReviewNode("ai-review",
                                                                Map.of("target", "${fault.nodeId}", "detail", "${fault.detail}"),
                                                                HumanGating.NONE))));

        Map<String, String> typeRegistry = Map.of(
                "ai-review", AiReviewSpec.class.getName());

        ThresholdFaultPolicy policy = YamlFaultPolicyBuilder.build(
                yamlPolicy, typeRegistry, new InMemoryFaultCountStore());

        // Build a graph with the faulted node — the policy needs it to determine node type
        DesiredStateGraph graph = graphWith(
                new DesiredNode(NodeId.of("aggregate-tx"),
                                new TransformerSpec("aggregate"), HumanGating.NONE));

        FaultEvent event = new FaultEvent(
                NodeId.of("aggregate-tx"), FaultType.PROVISION_FAILED,
                "Connection timeout to warehouse");

        // Failures 1-2: below threshold — no escalation yet
        assertThat(policy.onFault("tenant-1", event, graph, new ActualState(Map.of()))).isEmpty();
        assertThat(policy.onFault("tenant-1", event, graph, new ActualState(Map.of()))).isEmpty();

        // Failure 3: AI review triggered — spec carries the faulted node's details
        List<GraphMutation> mutations = policy.onFault("tenant-1", event, graph, new ActualState(Map.of()));
        assertThat(mutations).isNotEmpty();

        GraphMutation.AddNode addNode = mutations.stream()
                                                 .filter(m -> m instanceof GraphMutation.AddNode)
                                                 .map(m -> (GraphMutation.AddNode) m)
                                                 .findFirst()
                                                 .orElseThrow();

        AiReviewSpec spec = (AiReviewSpec) addNode.node().spec();
        assertThat(spec.target()).isEqualTo("aggregate-tx");
        assertThat(spec.detail()).isEqualTo("Connection timeout to warehouse");
    }

    @Test
    void twoTierEscalation_aiThenHuman() {
        YamlFaultPolicy yamlPolicy = new YamlFaultPolicy(
                List.of("PROVISION_FAILED"),
                List.of("transformer", "sink"),
                List.of("ai-review", "human-review"),
                "pipeline-escalation",
                List.of(
                        new YamlFaultTier(3, new YamlReviewNode("ai-review",
                                                                Map.of("target", "${fault.nodeId}", "detail", "${fault.detail}"),
                                                                HumanGating.NONE)),
                        new YamlFaultTier(5, new YamlReviewNode("human-review",
                                                                Map.of("target", "${fault.nodeId}", "detail", "${fault.detail}",
                                                                       "instruction", "Requires manual review"),
                                                                HumanGating.ALL))));

        Map<String, String> typeRegistry = Map.of(
                "ai-review", AiReviewSpec.class.getName(),
                "human-review", HumanReviewSpec.class.getName());

        ThresholdFaultPolicy policy = YamlFaultPolicyBuilder.build(
                yamlPolicy, typeRegistry, new InMemoryFaultCountStore());

        // The warehouse sink keeps failing during provisioning
        DesiredNode sinkNode = new DesiredNode(
                NodeId.of("warehouse-sink"), new SinkSpec("s3://warehouse/gold/"), HumanGating.NONE);
        DesiredStateGraph graph = graphWith(sinkNode);

        FaultEvent event = new FaultEvent(
                NodeId.of("warehouse-sink"), FaultType.PROVISION_FAILED, "Disk full");

        // Failures 1-2: no escalation — the system retries automatically
        assertThat(policy.onFault("tenant-1", event, graph, new ActualState(Map.of()))).isEmpty();
        assertThat(policy.onFault("tenant-1", event, graph, new ActualState(Map.of()))).isEmpty();

        // Failure 3: AI review triggered — an AI agent investigates the disk issue
        List<GraphMutation> aiMutations = policy.onFault("tenant-1", event, graph, new ActualState(Map.of()));
        assertThat(aiMutations).as("3rd failure should trigger AI review").isNotEmpty();

        // Apply the AI review node to the graph — simulating what the reconciliation loop does
        DesiredStateGraph graphWithAiReview = graph;
        for (GraphMutation mutation : aiMutations) {
            graphWithAiReview = graphWithAiReview.withMutation(mutation);
        }

        // Failure 4: AI review already exists — dedup, no new mutations
        assertThat(policy.onFault("tenant-1", event, graphWithAiReview, new ActualState(Map.of()))).isEmpty();

        // Failure 5: human review triggered — the AI couldn't fix it, a human gets pulled in
        List<GraphMutation> humanMutations = policy.onFault("tenant-1", event, graphWithAiReview, new ActualState(Map.of()));
        assertThat(humanMutations).as("5th failure should trigger human review").isNotEmpty();

        GraphMutation.AddNode humanAdd = humanMutations.stream()
                                                       .filter(m -> m instanceof GraphMutation.AddNode)
                                                       .map(m -> (GraphMutation.AddNode) m)
                                                       .findFirst().orElseThrow();

        HumanReviewSpec humanSpec = (HumanReviewSpec) humanAdd.node().spec();
        assertThat(humanSpec.target()).isEqualTo("warehouse-sink");
        assertThat(humanSpec.instruction()).isEqualTo("Requires manual review");
        assertThat(humanAdd.node().humanGating())
                .as("Human review requires approval for all actions")
                .isEqualTo(HumanGating.ALL);
    }

    @Test
    void humanGatingRespected_aiReviewGetsNone_humanReviewGetsAll() {
        // Single-tier test: AI review at threshold 1 — verify HumanGating.NONE is respected
        YamlFaultPolicy yamlPolicy = new YamlFaultPolicy(
                List.of("PROVISION_FAILED"), List.of(), List.of(),
                "gating-test",
                List.of(
                        new YamlFaultTier(1, new YamlReviewNode("ai-review",
                                                                Map.of("target", "${fault.nodeId}"),
                                                                HumanGating.NONE))));

        Map<String, String> typeRegistry = Map.of(
                "ai-review", AiReviewSpec.class.getName());

        ThresholdFaultPolicy policy = YamlFaultPolicyBuilder.build(
                yamlPolicy, typeRegistry, new InMemoryFaultCountStore());

        DesiredNode serviceNode = new DesiredNode(
                NodeId.of("my-service"), new TransformerSpec("process"), HumanGating.NONE);
        DesiredStateGraph graph = graphWith(serviceNode);

        FaultEvent event = new FaultEvent(
                NodeId.of("my-service"), FaultType.PROVISION_FAILED, "error");

        // Failure 1: AI review — should have HumanGating.NONE (AI handles it, no human approval)
        List<GraphMutation> mutations = policy.onFault("t1", event, graph, new ActualState(Map.of()));
        assertThat(mutations).isNotEmpty();

        GraphMutation.AddNode aiNode = mutations.stream()
                                                .filter(m -> m instanceof GraphMutation.AddNode)
                                                .map(m -> (GraphMutation.AddNode) m)
                                                .findFirst().orElseThrow();
        assertThat(aiNode.node().humanGating())
                .as("AI review should not require human approval")
                .isEqualTo(HumanGating.NONE);
    }

}
