package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ApprovalCheckResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.HumanNodeHandler;
import io.casehub.desiredstate.api.LifecycleStepExecutor;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeProvisionerRouter;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.PendingApprovalHandler;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.StepOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NodeStepExecutorTest {

    private static final NodeType TEST_TYPE = NodeType.of("test");
    private static final NodeSpec TEST_SPEC = new NodeSpec() {
        @Override
        public NodeType nodeType() {return TEST_TYPE;}
    };

    private NodeProvisionerRouter  router;
    private HumanNodeHandler       humanNodeHandler;
    private PendingApprovalHandler pendingApprovalHandler;
    private LifecycleStepExecutor  lifecycleStepExecutor;
    private DesiredStateGraph      graph;
    private NodeStepExecutor       executor;

    @BeforeEach
    void setUp() {
        router                 = mock(NodeProvisionerRouter.class);
        humanNodeHandler       = mock(HumanNodeHandler.class);
        pendingApprovalHandler = mock(PendingApprovalHandler.class);
        lifecycleStepExecutor  = mock(LifecycleStepExecutor.class);
        graph                  = mock(DesiredStateGraph.class);
        executor               = new NodeStepExecutor(router, humanNodeHandler, pendingApprovalHandler, lifecycleStepExecutor);
    }

    @Test
    void provision_alreadyConverged_mapsToAlreadyConvergedOutcome() {
        var node = new DesiredNode(NodeId.of("n1"), TEST_SPEC, HumanGating.NONE);
        when(pendingApprovalHandler.check(any(), any(), any())).thenReturn(new ApprovalCheckResult.None());
        when(router.provision(any(), any())).thenReturn(new ProvisionResult.AlreadyConverged());

        StepOutcome outcome = executor.execute(node, StepAction.PROVISION, graph, "t1");

        assertInstanceOf(StepOutcome.AlreadyConverged.class, outcome);
    }

    @Test
    void provision_alreadyConverged_runsPostProvisionHooks() {
        var hooks = new io.casehub.desiredstate.api.HookDescriptor(
                java.util.List.of(),
                java.util.List.of(new io.casehub.desiredstate.api.LifecycleStep.Verify("http://check", 5)),
                java.util.List.of(),
                java.util.List.of()
        );
        var node = new DesiredNode(NodeId.of("n1"), TEST_SPEC, HumanGating.NONE, hooks);
        when(pendingApprovalHandler.check(any(), any(), any())).thenReturn(new ApprovalCheckResult.None());
        when(router.provision(any(), any())).thenReturn(new ProvisionResult.AlreadyConverged());
        when(lifecycleStepExecutor.execute(any(), any())).thenReturn(new StepOutcome.Succeeded());

        StepOutcome outcome = executor.execute(node, StepAction.PROVISION, graph, "t1");

        assertInstanceOf(StepOutcome.AlreadyConverged.class, outcome);
        org.mockito.Mockito.verify(lifecycleStepExecutor).execute(any(), eq("t1"));
    }
}
