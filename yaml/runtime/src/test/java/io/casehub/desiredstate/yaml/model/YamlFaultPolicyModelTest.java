package io.casehub.desiredstate.yaml.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.api.HumanGating;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that YAML fault policy declarations deserialize correctly.
 *
 * Scenario: a data pipeline where transformation stages can fail.
 * After 3 failures, an AI agent reviews the issue. After 5 failures,
 * a human operator is pulled in with a work item.
 */
class YamlFaultPolicyModelTest {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Test
    void pipelineEscalation_deserializesAllFields() throws Exception {
        // A real operator would write this to protect their gold-layer transforms
        String yaml = """
                desiredState:
                  namespace: pipeline
                  name: medallion
                variables: {}
                nodes: {}
                faultPolicy:
                  - faultTypes: [PROVISION_FAILED]
                    nodeTypes: [transformer, sink]
                    ignoreTypes: [ai-review, human-review]
                    namespace: pipeline-escalation
                    tiers:
                      - threshold: 3
                        reviewNode:
                          type: ai-review
                          spec:
                            target: "${fault.nodeId}"
                            detail: "${fault.detail}"
                      - threshold: 5
                        reviewNode:
                          type: human-review
                          humanGating: ALL
                          spec:
                            target: "${fault.nodeId}"
                            detail: "${fault.detail}"
                            instruction: "Requires manual review"
                """;

        YamlGraph graph = yamlMapper.readValue(yaml, YamlGraph.class);

        assertThat(graph.faultPolicy()).hasSize(1);

        YamlFaultPolicy policy = graph.faultPolicy().getFirst();
        assertThat(policy.faultTypes()).containsExactly("PROVISION_FAILED");
        assertThat(policy.nodeTypes()).containsExactly("transformer", "sink");
        assertThat(policy.ignoreTypes()).containsExactly("ai-review", "human-review");
        assertThat(policy.namespace()).isEqualTo("pipeline-escalation");

        assertThat(policy.tiers()).hasSize(2);

        // Tier 1: AI review at 3 failures
        YamlFaultTier aiTier = policy.tiers().get(0);
        assertThat(aiTier.threshold()).isEqualTo(3);
        assertThat(aiTier.reviewNode().type()).isEqualTo("ai-review");
        assertThat(aiTier.reviewNode().spec()).containsEntry("target", "${fault.nodeId}");
        assertThat(aiTier.reviewNode().humanGating()).isEqualTo(HumanGating.NONE);

        // Tier 2: human review at 5 failures — requires human approval for all actions
        YamlFaultTier humanTier = policy.tiers().get(1);
        assertThat(humanTier.threshold()).isEqualTo(5);
        assertThat(humanTier.reviewNode().type()).isEqualTo("human-review");
        assertThat(humanTier.reviewNode().humanGating()).isEqualTo(HumanGating.ALL);
        assertThat(humanTier.reviewNode().spec())
                .containsEntry("instruction", "Requires manual review");
    }

    @Test
    void multiplePolicies_differentFaultTypes() throws Exception {
        // Real pipelines might have separate escalation for provision vs deprovision
        String yaml = """
                desiredState:
                  namespace: pipeline
                  name: medallion
                variables: {}
                nodes: {}
                faultPolicy:
                  - faultTypes: [PROVISION_FAILED]
                    nodeTypes: [transformer]
                    namespace: provision-escalation
                    tiers:
                      - threshold: 3
                        reviewNode:
                          type: ai-review
                          spec:
                            target: "${fault.nodeId}"
                  - faultTypes: [DEPROVISION_FAILED]
                    nodeTypes: [sink]
                    namespace: deprovision-escalation
                    tiers:
                      - threshold: 1
                        reviewNode:
                          type: human-review
                          humanGating: ALL
                          spec:
                            target: "${fault.nodeId}"
                            reason: "Deprovision failures need immediate attention"
                """;

        YamlGraph graph = yamlMapper.readValue(yaml, YamlGraph.class);

        assertThat(graph.faultPolicy()).hasSize(2);
        assertThat(graph.faultPolicy().get(0).namespace()).isEqualTo("provision-escalation");
        assertThat(graph.faultPolicy().get(1).namespace()).isEqualTo("deprovision-escalation");
        // Deprovision escalates on first failure — it's more dangerous
        assertThat(graph.faultPolicy().get(1).tiers().getFirst().threshold()).isEqualTo(1);
    }

    @Test
    void defaultsApplied_whenFieldsOmitted() throws Exception {
        // Minimal policy — only required fields
        String yaml = """
                desiredState:
                  namespace: test
                  name: minimal
                variables: {}
                nodes: {}
                faultPolicy:
                  - faultTypes: [PROVISION_FAILED]
                    namespace: minimal
                    tiers:
                      - threshold: 3
                        reviewNode:
                          type: review-node
                          spec: {}
                """;

        YamlGraph graph = yamlMapper.readValue(yaml, YamlGraph.class);
        YamlFaultPolicy policy = graph.faultPolicy().getFirst();

        assertThat(policy.nodeTypes()).isEmpty();
        assertThat(policy.ignoreTypes()).isEmpty();
        assertThat(policy.tiers().getFirst().reviewNode().humanGating())
                .isEqualTo(HumanGating.NONE);
    }

    @Test
    void noFaultPolicy_defaultsToEmptyList() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: simple
                variables: {}
                nodes: {}
                """;

        YamlGraph graph = yamlMapper.readValue(yaml, YamlGraph.class);
        assertThat(graph.faultPolicy()).isEmpty();
    }
}
