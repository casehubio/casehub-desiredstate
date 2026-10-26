package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DriftContext;
import io.casehub.desiredstate.api.DriftDecision;
import io.casehub.desiredstate.api.DriftPolicy;
import io.casehub.desiredstate.api.ExemptionSpec;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.RevertCondition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicDriftPolicyEngineTest {

    private static final NodeId NODE = NodeId.of("n1");
    private static final NodeType TYPE = NodeType.of("test");
    private static final DesiredNode DESIRED = new DesiredNode(NODE, new TestSpec(TYPE), HumanGating.NONE);

    @Test
    void emptyEngineReturnsReconcile() {
        var engine = new DynamicDriftPolicyEngine(List.of());
        var result = engine.evaluate(NODE, NodeStatus.DRIFTED, DESIRED, driftContext());
        assertThat(result).isEqualTo(DriftDecision.reconcile());
    }

    @Test
    void staticPolicyExemptsThenReturnsExempt() {
        var exemption = new ExemptionSpec(new RevertCondition.Never(), java.util.Map.of());
        DriftPolicy policy = (nodeId, status, node, ctx) -> new DriftDecision.Exempt(exemption);
        var engine = new DynamicDriftPolicyEngine(List.of(policy));

        var result = engine.evaluate(NODE, NodeStatus.DRIFTED, DESIRED, driftContext());
        assertThat(result).isInstanceOf(DriftDecision.Exempt.class);
    }

    @Test
    void addPolicyDynamically() {
        var engine = new DynamicDriftPolicyEngine(List.of());
        assertThat(engine.evaluate(NODE, NodeStatus.DRIFTED, DESIRED, driftContext()))
            .isEqualTo(DriftDecision.reconcile());

        var exemption = new ExemptionSpec(new RevertCondition.Never(), java.util.Map.of());
        DriftPolicy policy = (nodeId, status, node, ctx) -> new DriftDecision.Exempt(exemption);
        engine.addPolicy(policy);

        assertThat(engine.evaluate(NODE, NodeStatus.DRIFTED, DESIRED, driftContext()))
            .isInstanceOf(DriftDecision.Exempt.class);
    }

    @Test
    void removePolicyDynamically() {
        var exemption = new ExemptionSpec(new RevertCondition.Never(), java.util.Map.of());
        DriftPolicy policy = (nodeId, status, node, ctx) -> new DriftDecision.Exempt(exemption);
        var engine = new DynamicDriftPolicyEngine(List.of(policy));
        engine.removePolicy(policy);

        assertThat(engine.evaluate(NODE, NodeStatus.DRIFTED, DESIRED, driftContext()))
            .isEqualTo(DriftDecision.reconcile());
    }

    @Test
    void firstExemptWins() {
        var exemption1 = new ExemptionSpec(new RevertCondition.Never(), java.util.Map.of("source", "policy-1"));
        var exemption2 = new ExemptionSpec(new RevertCondition.Never(), java.util.Map.of("source", "policy-2"));
        DriftPolicy p1 = (nodeId, status, node, ctx) -> new DriftDecision.Exempt(exemption1);
        DriftPolicy p2 = (nodeId, status, node, ctx) -> new DriftDecision.Exempt(exemption2);
        var engine = new DynamicDriftPolicyEngine(List.of(p1, p2));

        var result = (DriftDecision.Exempt) engine.evaluate(NODE, NodeStatus.DRIFTED, DESIRED, driftContext());
        assertThat(result.spec().metadata().get("source")).isEqualTo("policy-1");
    }

    private record TestSpec(NodeType nodeType) implements NodeSpec {}

    private static DriftContext driftContext() {
        return new DriftContext("tenant-1", new DefaultDesiredStateGraphFactory().empty(),
            new io.casehub.desiredstate.api.ActualState(java.util.Map.of()));
    }
}
