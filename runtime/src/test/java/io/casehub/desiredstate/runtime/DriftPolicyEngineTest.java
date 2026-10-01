package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

class DriftPolicyEngineTest {

    private static final NodeId                          NODE         = NodeId.of("n1");
    private static final NodeStatus                      STATUS       = NodeStatus.DRIFTED;
    private static final DesiredNode                     DESIRED_NODE =
            new DesiredNode(NODE, new TestSpec(), HumanGating.NONE);
    private static final DefaultDesiredStateGraphFactory FACTORY      = new DefaultDesiredStateGraphFactory();
    private static final DriftContext                    CTX          = new DriftContext(
            "tenant", FACTORY.of(List.of(DESIRED_NODE), List.of()),
            new ActualState(Map.of(NODE, NodeStatus.DRIFTED)));

    @Test
    void emptyPolicies_returnsReconcile() {
        var engine = new DriftPolicyEngine(List.of());
        assertInstanceOf(DriftDecision.Reconcile.class,
                         engine.evaluate(NODE, STATUS, DESIRED_NODE, CTX));
    }

    @Test
    void singlePolicy_exempt() {
        DriftPolicy alwaysExempt = (id, s, n, c) ->
                                           DriftDecision.exempt(new ExemptionSpec(new RevertCondition.Never(), Map.of()));
        var engine = new DriftPolicyEngine(List.of(alwaysExempt));
        assertInstanceOf(DriftDecision.Exempt.class,
                         engine.evaluate(NODE, STATUS, DESIRED_NODE, CTX));
    }

    @Test
    void singlePolicy_reconcile() {
        DriftPolicy alwaysReconcile = (id, s, n, c) -> DriftDecision.reconcile();
        var         engine          = new DriftPolicyEngine(List.of(alwaysReconcile));
        assertInstanceOf(DriftDecision.Reconcile.class,
                         engine.evaluate(NODE, STATUS, DESIRED_NODE, CTX));
    }

    @Test
    void firstExemptWins() {
        DriftPolicy reconcile = (id, s, n, c) -> DriftDecision.reconcile();
        DriftPolicy exempt = (id, s, n, c) ->
                                     DriftDecision.exempt(new ExemptionSpec(new RevertCondition.Never(), Map.of()));
        DriftPolicy shouldNotBeCalled = (id, s, n, c) -> {
            fail("Should not be called after EXEMPT");
            return DriftDecision.reconcile();
        };
        var engine = new DriftPolicyEngine(List.of(reconcile, exempt, shouldNotBeCalled));
        assertInstanceOf(DriftDecision.Exempt.class,
                         engine.evaluate(NODE, STATUS, DESIRED_NODE, CTX));
    }

    @Test
    void allReconcile_returnsReconcile() {
        DriftPolicy r1     = (id, s, n, c) -> DriftDecision.reconcile();
        DriftPolicy r2     = (id, s, n, c) -> DriftDecision.reconcile();
        var         engine = new DriftPolicyEngine(List.of(r1, r2));
        assertInstanceOf(DriftDecision.Reconcile.class,
                         engine.evaluate(NODE, STATUS, DESIRED_NODE, CTX));
    }

    private static class TestSpec implements NodeSpec {
        @Override
        public NodeType nodeType() {return NodeType.of("test");}
    }
}
