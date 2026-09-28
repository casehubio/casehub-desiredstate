package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SuspendResumeTypesTest {

    @Test
    void suspendResultVariants() {
        SuspendResult success = new SuspendResult.Success();
        SuspendResult failed  = new SuspendResult.Failed("reason");
        SuspendResult pending = new SuspendResult.PendingApproval(NodeId.of("n1"), "plan-ref");
        assertInstanceOf(SuspendResult.Success.class, success);
        assertInstanceOf(SuspendResult.Failed.class, failed);
        assertEquals("reason", ((SuspendResult.Failed) failed).reason());
        assertEquals(NodeId.of("n1"), ((SuspendResult.PendingApproval) pending).nodeId());
    }

    @Test
    void resumeResultVariants() {
        ResumeResult success = new ResumeResult.Success();
        ResumeResult failed  = new ResumeResult.Failed("reason");
        ResumeResult pending = new ResumeResult.PendingApproval(NodeId.of("n1"), "plan-ref");
        assertInstanceOf(ResumeResult.Success.class, success);
        assertInstanceOf(ResumeResult.Failed.class, failed);
        assertEquals("reason", ((ResumeResult.Failed) failed).reason());
    }

    @Test
    void suspendContextRejectsNullTenancy() {
        assertThrows(NullPointerException.class,
                     () -> new SuspendContext(null, null));
    }

    @Test
    void resumeContextRejectsNullTenancy() {
        assertThrows(NullPointerException.class,
                     () -> new ResumeContext(null, null));
    }

    @Test
    void nodeStatusSuspended() {
        assertNotNull(NodeStatus.SUSPENDED);
        assertEquals(5, NodeStatus.values().length);
    }
}
