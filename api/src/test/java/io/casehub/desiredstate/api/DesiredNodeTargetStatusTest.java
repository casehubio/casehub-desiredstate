package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesiredNodeTargetStatusTest {
    private static final NodeSpec SPEC = new NodeSpec() {
        @Override public NodeType nodeType() { return NodeType.of("test"); }
    };

    @Test
    void threeArgConstructorDefaultsToActive() {
        DesiredNode node = new DesiredNode(NodeId.of("n1"), SPEC, HumanGating.NONE);
        assertEquals(TargetStatus.ACTIVE, node.targetStatus());
    }

    @Test
    void fourArgConstructorDefaultsToActive() {
        DesiredNode node = new DesiredNode(NodeId.of("n1"), SPEC, HumanGating.NONE, null);
        assertEquals(TargetStatus.ACTIVE, node.targetStatus());
    }

    @Test
    void fiveArgConstructorSetsSuspended() {
        DesiredNode node = new DesiredNode(NodeId.of("n1"), SPEC, HumanGating.NONE, null, TargetStatus.SUSPENDED);
        assertEquals(TargetStatus.SUSPENDED, node.targetStatus());
    }

    @Test
    void targetStatusNullRejected() {
        assertThrows(NullPointerException.class,
            () -> new DesiredNode(NodeId.of("n1"), SPEC, HumanGating.NONE, null, null));
    }

    @Test
    void allSatisfiedExists() {
        CompletionCondition cond = CompletionCondition.allSatisfied();
        assertNotNull(cond);
    }

    @Test
    void nodeProvisionerDefaultSuspendReturnsFailed() {
        NodeProvisioner provisioner = new NodeProvisioner() {
            @Override public java.util.Set<NodeType> handledTypes() { return java.util.Set.of(); }
            @Override public ProvisionResult provision(DesiredNode n, ProvisionContext c) { return new ProvisionResult.Success(); }
            @Override public DeprovisionResult deprovision(DesiredNode n, DeprovisionContext c) { return new DeprovisionResult.Success(); }
        };
        DesiredNode node = new DesiredNode(NodeId.of("n1"), SPEC, HumanGating.NONE);
        SuspendResult result = provisioner.suspend(node, (SuspendContext) null);
        assertInstanceOf(SuspendResult.Failed.class, result);
    }

    @Test
    void nodeProvisionerDefaultResumeReturnsFailed() {
        NodeProvisioner provisioner = new NodeProvisioner() {
            @Override public java.util.Set<NodeType> handledTypes() { return java.util.Set.of(); }
            @Override public ProvisionResult provision(DesiredNode n, ProvisionContext c) { return new ProvisionResult.Success(); }
            @Override public DeprovisionResult deprovision(DesiredNode n, DeprovisionContext c) { return new DeprovisionResult.Success(); }
        };
        DesiredNode node = new DesiredNode(NodeId.of("n1"), SPEC, HumanGating.NONE);
        ResumeResult result = provisioner.resume(node, (ResumeContext) null);
        assertInstanceOf(ResumeResult.Failed.class, result);
    }

    @Test
    void nodeProvisionerDefaultNotStateful() {
        NodeProvisioner provisioner = new NodeProvisioner() {
            @Override public java.util.Set<NodeType> handledTypes() { return java.util.Set.of(); }
            @Override public ProvisionResult provision(DesiredNode n, ProvisionContext c) { return new ProvisionResult.Success(); }
            @Override public DeprovisionResult deprovision(DesiredNode n, DeprovisionContext c) { return new DeprovisionResult.Success(); }
        };
        assertFalse(provisioner.supportsStatefulLifecycle());
    }
}
