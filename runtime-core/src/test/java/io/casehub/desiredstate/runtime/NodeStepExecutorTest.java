package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ApprovalCheckResult;
import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HookDescriptor;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.HumanNodeHandler;
import io.casehub.desiredstate.api.LifecycleStep;
import io.casehub.desiredstate.api.LifecycleStepExecutor;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeProvisionerRouter;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.PendingApprovalHandler;
import io.casehub.desiredstate.api.PlanApproval;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.ResumeContext;
import io.casehub.desiredstate.api.ResumeResult;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.SuspendContext;
import io.casehub.desiredstate.api.SuspendResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeStepExecutorTest {

    private static final NodeType TEST_TYPE = NodeType.of("test");
    private static final NodeSpec TEST_SPEC = new NodeSpec() {
        @Override
        public NodeType nodeType() { return TEST_TYPE; }
    };
    private static final String TENANT = "t1";

    private NodeProvisionerRouter  router;
    private HumanNodeHandler       humanNodeHandler;
    private PendingApprovalHandler  pendingApprovalHandler;
    private LifecycleStepExecutor   lifecycleStepExecutor;
    private DesiredStateGraph       graph;
    private NodeStepExecutor        executor;

    @BeforeEach
    void setUp() {
        router                 = mock(NodeProvisionerRouter.class);
        humanNodeHandler       = mock(HumanNodeHandler.class);
        pendingApprovalHandler = mock(PendingApprovalHandler.class);
        lifecycleStepExecutor  = mock(LifecycleStepExecutor.class);
        graph                  = mock(DesiredStateGraph.class);
        executor               = new NodeStepExecutor(router, humanNodeHandler,
                                                       pendingApprovalHandler, lifecycleStepExecutor);
    }

    private DesiredNode node() {
        return new DesiredNode(NodeId.of("n1"), TEST_SPEC, HumanGating.NONE);
    }

    private DesiredNode nodeWithGating(HumanGating gating) {
        return new DesiredNode(NodeId.of("n1"), TEST_SPEC, gating);
    }

    private DesiredNode nodeWithHooks(HookDescriptor hooks) {
        return new DesiredNode(NodeId.of("n1"), TEST_SPEC, HumanGating.NONE, hooks);
    }

    private void approvalReturnsNone() {
        when(pendingApprovalHandler.check(any(), any(), any())).thenReturn(new ApprovalCheckResult.None());
    }

    private PlanApproval sampleApproval() {
        return new PlanApproval("plan-ref-1", "admin", Instant.parse("2026-01-01T00:00:00Z"));
    }

    // ── execute() dispatch ─────────────────────────────────────────────

    @Test
    void execute_routesProvision() {
        approvalReturnsNone();
        when(router.provision(any(), any())).thenReturn(new ProvisionResult.Success());
        executor.execute(node(), StepAction.PROVISION, graph, TENANT);
        verify(router).provision(any(), any());
    }

    @Test
    void execute_routesDeprovision() {
        approvalReturnsNone();
        when(router.deprovision(any(), any())).thenReturn(new DeprovisionResult.Success());
        executor.execute(node(), StepAction.DEPROVISION, graph, TENANT);
        verify(router).deprovision(any(), any());
    }

    @Test
    void execute_routesSuspend() {
        approvalReturnsNone();
        when(router.suspend(any(), any())).thenReturn(new SuspendResult.Success());
        executor.execute(node(), StepAction.SUSPEND, graph, TENANT);
        verify(router).suspend(any(), any());
    }

    @Test
    void execute_routesResume() {
        approvalReturnsNone();
        when(router.resume(any(), any())).thenReturn(new ResumeResult.Success());
        executor.execute(node(), StepAction.RESUME, graph, TENANT);
        verify(router).resume(any(), any());
    }

    // ── Provision ──────────────────────────────────────────────────────

    @Nested
    class Provision {

        @Test
        void success_mapsToSucceeded() {
            approvalReturnsNone();
            when(router.provision(any(), any())).thenReturn(new ProvisionResult.Success());

            StepOutcome outcome = executor.executeProvision(node(), graph, TENANT);

            assertInstanceOf(StepOutcome.Succeeded.class, outcome);
        }

        @Test
        void alreadyConverged_mapsToAlreadyConverged() {
            approvalReturnsNone();
            when(router.provision(any(), any())).thenReturn(new ProvisionResult.AlreadyConverged());

            StepOutcome outcome = executor.executeProvision(node(), graph, TENANT);

            assertInstanceOf(StepOutcome.AlreadyConverged.class, outcome);
        }

        @Test
        void failed_mapsToFailed() {
            approvalReturnsNone();
            when(router.provision(any(), any())).thenReturn(new ProvisionResult.Failed("disk full"));

            StepOutcome outcome = executor.executeProvision(node(), graph, TENANT);

            var failed = assertInstanceOf(StepOutcome.Failed.class, outcome);
            assertEquals("disk full", failed.reason());
        }

        @Test
        void pendingApproval_callsRecordPending() {
            approvalReturnsNone();
            when(router.provision(any(), any()))
                    .thenReturn(new ProvisionResult.PendingApproval(NodeId.of("n1"), "plan-42"));
            when(pendingApprovalHandler.recordPending(any(), any(), any(), any()))
                    .thenReturn(new StepOutcome.Skipped("awaiting approval"));

            StepOutcome outcome = executor.executeProvision(node(), graph, TENANT);

            verify(pendingApprovalHandler).recordPending(any(), eq(StepAction.PROVISION), eq(TENANT), eq("plan-42"));
            assertInstanceOf(StepOutcome.Skipped.class, outcome);
        }

        @Test
        void humanGated_delegatesToHumanNodeHandler() {
            var node = nodeWithGating(HumanGating.PROVISION_ONLY);
            when(humanNodeHandler.onProvision(any(), any())).thenReturn(new StepOutcome.Succeeded());

            StepOutcome outcome = executor.executeProvision(node, graph, TENANT);

            assertInstanceOf(StepOutcome.Succeeded.class, outcome);
            verify(humanNodeHandler).onProvision(eq(node), any(ProvisionContext.class));
            verify(router, never()).provision(any(), any());
        }

        @Test
        void humanGated_allGating_delegatesToHumanNodeHandler() {
            var node = nodeWithGating(HumanGating.ALL);
            when(humanNodeHandler.onProvision(any(), any())).thenReturn(new StepOutcome.Skipped("manual"));

            StepOutcome outcome = executor.executeProvision(node, graph, TENANT);

            assertInstanceOf(StepOutcome.Skipped.class, outcome);
            verify(router, never()).provision(any(), any());
        }

        @Test
        void notHumanGated_deprovisionOnlyDoesNotGateProvision() {
            var node = nodeWithGating(HumanGating.DEPROVISION_ONLY);
            approvalReturnsNone();
            when(router.provision(any(), any())).thenReturn(new ProvisionResult.Success());

            executor.executeProvision(node, graph, TENANT);

            verify(router).provision(any(), any());
            verify(humanNodeHandler, never()).onProvision(any(), any());
        }

        @Test
        void approvalPending_returnsSkipped() {
            when(pendingApprovalHandler.check(any(), any(), any()))
                    .thenReturn(new ApprovalCheckResult.Pending("plan-99"));

            StepOutcome outcome = executor.executeProvision(node(), graph, TENANT);

            assertInstanceOf(StepOutcome.Skipped.class, outcome);
            verify(router, never()).provision(any(), any());
        }

        @Test
        void approvalRejected_acknowledgesAndReturnsRejected() {
            when(pendingApprovalHandler.check(any(), any(), any()))
                    .thenReturn(new ApprovalCheckResult.Rejected("plan-99", "policy violation"));

            StepOutcome outcome = executor.executeProvision(node(), graph, TENANT);

            var rejected = assertInstanceOf(StepOutcome.Rejected.class, outcome);
            assertEquals("approval rejected: policy violation", rejected.reason());
            verify(pendingApprovalHandler).acknowledgeRejection(any(), eq(StepAction.PROVISION), eq(TENANT));
            verify(router, never()).provision(any(), any());
        }

        @Test
        void approvalApproved_passesApprovalToProvisioner() {
            PlanApproval approval = sampleApproval();
            when(pendingApprovalHandler.check(any(), any(), any()))
                    .thenReturn(new ApprovalCheckResult.Approved(approval));
            when(router.provision(any(), any())).thenReturn(new ProvisionResult.Success());

            executor.executeProvision(node(), graph, TENANT);

            ArgumentCaptor<ProvisionContext> captor = ArgumentCaptor.forClass(ProvisionContext.class);
            verify(router).provision(any(), captor.capture());
            assertNotNull(captor.getValue().approval());
            assertEquals("plan-ref-1", captor.getValue().approval().planReference());
        }

        @Test
        void preHookFails_returnsFailedBeforeProvisioning() {
            var hooks = new HookDescriptor(
                    List.of(new LifecycleStep.Verify("http://check", 5)),
                    List.of(), List.of(), List.of());
            var node = nodeWithHooks(hooks);
            approvalReturnsNone();
            when(lifecycleStepExecutor.execute(any(), any()))
                    .thenReturn(new StepOutcome.Failed("health check failed"));

            StepOutcome outcome = executor.executeProvision(node, graph, TENANT);

            var failed = assertInstanceOf(StepOutcome.Failed.class, outcome);
            assertEquals("pre-provision hook failed: health check failed", failed.reason());
            verify(router, never()).provision(any(), any());
        }

        @Test
        void preHookSucceeds_continuesProvisioning() {
            var hooks = new HookDescriptor(
                    List.of(new LifecycleStep.Verify("http://check", 5)),
                    List.of(), List.of(), List.of());
            var node = nodeWithHooks(hooks);
            approvalReturnsNone();
            when(lifecycleStepExecutor.execute(any(), any())).thenReturn(new StepOutcome.Succeeded());
            when(router.provision(any(), any())).thenReturn(new ProvisionResult.Success());

            StepOutcome outcome = executor.executeProvision(node, graph, TENANT);

            assertInstanceOf(StepOutcome.Succeeded.class, outcome);
            verify(router).provision(any(), any());
        }

        @Test
        void success_runsPostProvisionHooks() {
            var hooks = new HookDescriptor(
                    List.of(), List.of(new LifecycleStep.Notify("slack", "deployed")),
                    List.of(), List.of());
            var node = nodeWithHooks(hooks);
            approvalReturnsNone();
            when(router.provision(any(), any())).thenReturn(new ProvisionResult.Success());
            when(lifecycleStepExecutor.execute(any(), any())).thenReturn(new StepOutcome.Succeeded());

            executor.executeProvision(node, graph, TENANT);

            verify(lifecycleStepExecutor).execute(any(LifecycleStep.Notify.class), eq(TENANT));
        }

        @Test
        void alreadyConverged_runsPostProvisionHooks() {
            var hooks = new HookDescriptor(
                    List.of(), List.of(new LifecycleStep.Verify("http://check", 5)),
                    List.of(), List.of());
            var node = nodeWithHooks(hooks);
            approvalReturnsNone();
            when(router.provision(any(), any())).thenReturn(new ProvisionResult.AlreadyConverged());
            when(lifecycleStepExecutor.execute(any(), any())).thenReturn(new StepOutcome.Succeeded());

            StepOutcome outcome = executor.executeProvision(node, graph, TENANT);

            assertInstanceOf(StepOutcome.AlreadyConverged.class, outcome);
            verify(lifecycleStepExecutor).execute(any(), eq(TENANT));
        }

        @Test
        void postHookFails_stillReturnsSucceeded() {
            var hooks = new HookDescriptor(
                    List.of(), List.of(new LifecycleStep.Notify("slack", "deployed")),
                    List.of(), List.of());
            var node = nodeWithHooks(hooks);
            approvalReturnsNone();
            when(router.provision(any(), any())).thenReturn(new ProvisionResult.Success());
            when(lifecycleStepExecutor.execute(any(), any()))
                    .thenReturn(new StepOutcome.Failed("notification failed"));

            StepOutcome outcome = executor.executeProvision(node, graph, TENANT);

            assertInstanceOf(StepOutcome.Succeeded.class, outcome);
        }

        @Test
        void noHooks_skipsHookLogic() {
            approvalReturnsNone();
            when(router.provision(any(), any())).thenReturn(new ProvisionResult.Success());

            StepOutcome outcome = executor.executeProvision(node(), graph, TENANT);

            assertInstanceOf(StepOutcome.Succeeded.class, outcome);
            verify(lifecycleStepExecutor, never()).execute(any(), any());
        }
    }

    // ── Deprovision ────────────────────────────────────────────────────

    @Nested
    class Deprovision {

        @Test
        void success_mapsToSucceeded() {
            approvalReturnsNone();
            when(router.deprovision(any(), any())).thenReturn(new DeprovisionResult.Success());

            StepOutcome outcome = executor.executeDeprovision(node(), graph, TENANT);

            assertInstanceOf(StepOutcome.Succeeded.class, outcome);
        }

        @Test
        void failed_mapsToFailed() {
            approvalReturnsNone();
            when(router.deprovision(any(), any())).thenReturn(new DeprovisionResult.Failed("in use"));

            StepOutcome outcome = executor.executeDeprovision(node(), graph, TENANT);

            var failed = assertInstanceOf(StepOutcome.Failed.class, outcome);
            assertEquals("in use", failed.reason());
        }

        @Test
        void pendingApproval_callsRecordPending() {
            approvalReturnsNone();
            when(router.deprovision(any(), any()))
                    .thenReturn(new DeprovisionResult.PendingApproval(NodeId.of("n1"), "plan-77"));
            when(pendingApprovalHandler.recordPending(any(), any(), any(), any()))
                    .thenReturn(new StepOutcome.Skipped("awaiting approval"));

            StepOutcome outcome = executor.executeDeprovision(node(), graph, TENANT);

            verify(pendingApprovalHandler).recordPending(any(), eq(StepAction.DEPROVISION), eq(TENANT), eq("plan-77"));
            assertInstanceOf(StepOutcome.Skipped.class, outcome);
        }

        @Test
        void humanGated_delegatesToHumanNodeHandler() {
            var node = nodeWithGating(HumanGating.DEPROVISION_ONLY);
            when(humanNodeHandler.onDeprovision(any(), any())).thenReturn(new StepOutcome.Succeeded());

            StepOutcome outcome = executor.executeDeprovision(node, graph, TENANT);

            assertInstanceOf(StepOutcome.Succeeded.class, outcome);
            verify(humanNodeHandler).onDeprovision(eq(node), any(DeprovisionContext.class));
            verify(router, never()).deprovision(any(), any());
        }

        @Test
        void notHumanGated_provisionOnlyDoesNotGateDeprovision() {
            var node = nodeWithGating(HumanGating.PROVISION_ONLY);
            approvalReturnsNone();
            when(router.deprovision(any(), any())).thenReturn(new DeprovisionResult.Success());

            executor.executeDeprovision(node, graph, TENANT);

            verify(router).deprovision(any(), any());
            verify(humanNodeHandler, never()).onDeprovision(any(), any());
        }

        @Test
        void approvalPending_returnsSkipped() {
            when(pendingApprovalHandler.check(any(), any(), any()))
                    .thenReturn(new ApprovalCheckResult.Pending("plan-88"));

            StepOutcome outcome = executor.executeDeprovision(node(), graph, TENANT);

            assertInstanceOf(StepOutcome.Skipped.class, outcome);
            verify(router, never()).deprovision(any(), any());
        }

        @Test
        void approvalRejected_acknowledgesAndReturnsRejected() {
            when(pendingApprovalHandler.check(any(), any(), any()))
                    .thenReturn(new ApprovalCheckResult.Rejected("plan-88", "not allowed"));

            StepOutcome outcome = executor.executeDeprovision(node(), graph, TENANT);

            var rejected = assertInstanceOf(StepOutcome.Rejected.class, outcome);
            assertEquals("approval rejected: not allowed", rejected.reason());
            verify(pendingApprovalHandler).acknowledgeRejection(any(), eq(StepAction.DEPROVISION), eq(TENANT));
        }

        @Test
        void approvalApproved_passesApprovalToProvisioner() {
            PlanApproval approval = sampleApproval();
            when(pendingApprovalHandler.check(any(), any(), any()))
                    .thenReturn(new ApprovalCheckResult.Approved(approval));
            when(router.deprovision(any(), any())).thenReturn(new DeprovisionResult.Success());

            executor.executeDeprovision(node(), graph, TENANT);

            ArgumentCaptor<DeprovisionContext> captor = ArgumentCaptor.forClass(DeprovisionContext.class);
            verify(router).deprovision(any(), captor.capture());
            assertNotNull(captor.getValue().approval());
            assertEquals("plan-ref-1", captor.getValue().approval().planReference());
        }

        @Test
        void preHookFails_returnsFailedBeforeDeprovisioning() {
            var hooks = new HookDescriptor(
                    List.of(), List.of(),
                    List.of(new LifecycleStep.Verify("http://drain-check", 10)),
                    List.of());
            var node = nodeWithHooks(hooks);
            approvalReturnsNone();
            when(lifecycleStepExecutor.execute(any(), any()))
                    .thenReturn(new StepOutcome.Failed("drain check failed"));

            StepOutcome outcome = executor.executeDeprovision(node, graph, TENANT);

            var failed = assertInstanceOf(StepOutcome.Failed.class, outcome);
            assertEquals("pre-deprovision hook failed: drain check failed", failed.reason());
            verify(router, never()).deprovision(any(), any());
        }

        @Test
        void success_runsPostDeprovisionHooks() {
            var hooks = new HookDescriptor(
                    List.of(), List.of(),
                    List.of(), List.of(new LifecycleStep.Notify("slack", "removed")));
            var node = nodeWithHooks(hooks);
            approvalReturnsNone();
            when(router.deprovision(any(), any())).thenReturn(new DeprovisionResult.Success());
            when(lifecycleStepExecutor.execute(any(), any())).thenReturn(new StepOutcome.Succeeded());

            executor.executeDeprovision(node, graph, TENANT);

            verify(lifecycleStepExecutor).execute(any(LifecycleStep.Notify.class), eq(TENANT));
        }

        @Test
        void postHookFails_stillReturnsSucceeded() {
            var hooks = new HookDescriptor(
                    List.of(), List.of(),
                    List.of(), List.of(new LifecycleStep.Notify("slack", "removed")));
            var node = nodeWithHooks(hooks);
            approvalReturnsNone();
            when(router.deprovision(any(), any())).thenReturn(new DeprovisionResult.Success());
            when(lifecycleStepExecutor.execute(any(), any()))
                    .thenReturn(new StepOutcome.Failed("notification failed"));

            StepOutcome outcome = executor.executeDeprovision(node, graph, TENANT);

            assertInstanceOf(StepOutcome.Succeeded.class, outcome);
        }
    }

    // ── Suspend ────────────────────────────────────────────────────────

    @Nested
    class Suspend {

        @Test
        void success_mapsToSucceeded() {
            approvalReturnsNone();
            when(router.suspend(any(), any())).thenReturn(new SuspendResult.Success());

            StepOutcome outcome = executor.executeSuspend(node(), graph, TENANT);

            assertInstanceOf(StepOutcome.Succeeded.class, outcome);
        }

        @Test
        void failed_mapsToFailed() {
            approvalReturnsNone();
            when(router.suspend(any(), any())).thenReturn(new SuspendResult.Failed("not suspendable"));

            StepOutcome outcome = executor.executeSuspend(node(), graph, TENANT);

            var failed = assertInstanceOf(StepOutcome.Failed.class, outcome);
            assertEquals("not suspendable", failed.reason());
        }

        @Test
        void pendingApproval_callsRecordPending() {
            approvalReturnsNone();
            when(router.suspend(any(), any()))
                    .thenReturn(new SuspendResult.PendingApproval(NodeId.of("n1"), "plan-55"));
            when(pendingApprovalHandler.recordPending(any(), any(), any(), any()))
                    .thenReturn(new StepOutcome.Skipped("awaiting approval"));

            StepOutcome outcome = executor.executeSuspend(node(), graph, TENANT);

            verify(pendingApprovalHandler).recordPending(any(), eq(StepAction.SUSPEND), eq(TENANT), eq("plan-55"));
            assertInstanceOf(StepOutcome.Skipped.class, outcome);
        }

        @Test
        void humanGated_delegatesToHumanNodeHandler() {
            var node = nodeWithGating(HumanGating.SUSPEND_ONLY);
            when(humanNodeHandler.onSuspend(any(), any())).thenReturn(new StepOutcome.Succeeded());

            StepOutcome outcome = executor.executeSuspend(node, graph, TENANT);

            assertInstanceOf(StepOutcome.Succeeded.class, outcome);
            verify(humanNodeHandler).onSuspend(eq(node), any(SuspendContext.class));
            verify(router, never()).suspend(any(), any());
        }

        @Test
        void approvalPending_returnsSkipped() {
            when(pendingApprovalHandler.check(any(), any(), any()))
                    .thenReturn(new ApprovalCheckResult.Pending("plan-55"));

            StepOutcome outcome = executor.executeSuspend(node(), graph, TENANT);

            assertInstanceOf(StepOutcome.Skipped.class, outcome);
            verify(router, never()).suspend(any(), any());
        }

        @Test
        void approvalRejected_acknowledgesAndReturnsRejected() {
            when(pendingApprovalHandler.check(any(), any(), any()))
                    .thenReturn(new ApprovalCheckResult.Rejected("plan-55", "maintenance window"));

            StepOutcome outcome = executor.executeSuspend(node(), graph, TENANT);

            var rejected = assertInstanceOf(StepOutcome.Rejected.class, outcome);
            assertEquals("approval rejected: maintenance window", rejected.reason());
            verify(pendingApprovalHandler).acknowledgeRejection(any(), eq(StepAction.SUSPEND), eq(TENANT));
        }

        @Test
        void approvalApproved_passesApprovalToRouter() {
            PlanApproval approval = sampleApproval();
            when(pendingApprovalHandler.check(any(), any(), any()))
                    .thenReturn(new ApprovalCheckResult.Approved(approval));
            when(router.suspend(any(), any())).thenReturn(new SuspendResult.Success());

            executor.executeSuspend(node(), graph, TENANT);

            ArgumentCaptor<SuspendContext> captor = ArgumentCaptor.forClass(SuspendContext.class);
            verify(router).suspend(any(), captor.capture());
            assertNotNull(captor.getValue().approval());
            assertEquals("plan-ref-1", captor.getValue().approval().planReference());
        }
    }

    // ── Resume ─────────────────────────────────────────────────────────

    @Nested
    class Resume {

        @Test
        void success_mapsToSucceeded() {
            approvalReturnsNone();
            when(router.resume(any(), any())).thenReturn(new ResumeResult.Success());

            StepOutcome outcome = executor.executeResume(node(), graph, TENANT);

            assertInstanceOf(StepOutcome.Succeeded.class, outcome);
        }

        @Test
        void failed_mapsToFailed() {
            approvalReturnsNone();
            when(router.resume(any(), any())).thenReturn(new ResumeResult.Failed("resource gone"));

            StepOutcome outcome = executor.executeResume(node(), graph, TENANT);

            var failed = assertInstanceOf(StepOutcome.Failed.class, outcome);
            assertEquals("resource gone", failed.reason());
        }

        @Test
        void pendingApproval_callsRecordPending() {
            approvalReturnsNone();
            when(router.resume(any(), any()))
                    .thenReturn(new ResumeResult.PendingApproval(NodeId.of("n1"), "plan-33"));
            when(pendingApprovalHandler.recordPending(any(), any(), any(), any()))
                    .thenReturn(new StepOutcome.Skipped("awaiting approval"));

            StepOutcome outcome = executor.executeResume(node(), graph, TENANT);

            verify(pendingApprovalHandler).recordPending(any(), eq(StepAction.RESUME), eq(TENANT), eq("plan-33"));
            assertInstanceOf(StepOutcome.Skipped.class, outcome);
        }

        @Test
        void humanGated_delegatesToHumanNodeHandler() {
            var node = nodeWithGating(HumanGating.RESUME_ONLY);
            when(humanNodeHandler.onResume(any(), any())).thenReturn(new StepOutcome.Succeeded());

            StepOutcome outcome = executor.executeResume(node, graph, TENANT);

            assertInstanceOf(StepOutcome.Succeeded.class, outcome);
            verify(humanNodeHandler).onResume(eq(node), any(ResumeContext.class));
            verify(router, never()).resume(any(), any());
        }

        @Test
        void approvalPending_returnsSkipped() {
            when(pendingApprovalHandler.check(any(), any(), any()))
                    .thenReturn(new ApprovalCheckResult.Pending("plan-33"));

            StepOutcome outcome = executor.executeResume(node(), graph, TENANT);

            assertInstanceOf(StepOutcome.Skipped.class, outcome);
            verify(router, never()).resume(any(), any());
        }

        @Test
        void approvalRejected_acknowledgesAndReturnsRejected() {
            when(pendingApprovalHandler.check(any(), any(), any()))
                    .thenReturn(new ApprovalCheckResult.Rejected("plan-33", "capacity issue"));

            StepOutcome outcome = executor.executeResume(node(), graph, TENANT);

            var rejected = assertInstanceOf(StepOutcome.Rejected.class, outcome);
            assertEquals("approval rejected: capacity issue", rejected.reason());
            verify(pendingApprovalHandler).acknowledgeRejection(any(), eq(StepAction.RESUME), eq(TENANT));
        }

        @Test
        void approvalApproved_passesApprovalToRouter() {
            PlanApproval approval = sampleApproval();
            when(pendingApprovalHandler.check(any(), any(), any()))
                    .thenReturn(new ApprovalCheckResult.Approved(approval));
            when(router.resume(any(), any())).thenReturn(new ResumeResult.Success());

            executor.executeResume(node(), graph, TENANT);

            ArgumentCaptor<ResumeContext> captor = ArgumentCaptor.forClass(ResumeContext.class);
            verify(router).resume(any(), captor.capture());
            assertNotNull(captor.getValue().approval());
            assertEquals("plan-ref-1", captor.getValue().approval().planReference());
        }
    }
}
