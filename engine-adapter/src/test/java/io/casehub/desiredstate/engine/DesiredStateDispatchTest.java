package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.DefaultNodeProvisionerRouter;
import io.casehub.desiredstate.testing.MockNodeProvisioner;
import io.casehub.desiredstate.testing.MockPendingApprovalHandler;
import io.casehub.engine.flow.CallableDispatchRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class DesiredStateDispatchTest {

    private DesiredStateExecutionRegistry executionRegistry;
    private MockPendingApprovalHandler approvalHandler;
    private CallableDispatchRegistry callRegistry;
    private DesiredStateGraph graph;
    private DesiredNode testNode;

    // Captures context passed to provisioner
    private ProvisionContext capturedProvisionContext;
    private DeprovisionContext capturedDeprovisionContext;
    private NodeProvisioner provisioner;

    @BeforeEach
    void setUp() {
        executionRegistry = new DesiredStateExecutionRegistry();
        approvalHandler = new MockPendingApprovalHandler();
        callRegistry = new CallableDispatchRegistry();

        testNode = new DesiredNode(
            NodeId.of("app"), NodeType.of("service"), new TestSpec(), false);

        DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
        graph = factory.of(List.of(testNode), List.of());

        capturedProvisionContext = null;
        capturedDeprovisionContext = null;
        provisioner = new NodeProvisioner() {
            @Override
            public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
                capturedProvisionContext = context;
                return new ProvisionResult.Success();
            }
            @Override
            public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
                capturedDeprovisionContext = context;
                return new DeprovisionResult.Success();
            }
            @Override
            public Set<NodeType> handledTypes() {
                return Set.of(NodeType.of("service"));
            }
        };

        NodeProvisionerRouter router = new DefaultNodeProvisionerRouter(List.of(provisioner));
        DesiredStateDispatch dispatch = new DesiredStateDispatch(
            router, approvalHandler, executionRegistry, callRegistry);
        dispatch.register();

        executionRegistry.register("exec-1", graph, "tenant-a");
    }

    @Test
    void provisionSuccess_checkNone() throws Exception {
        Map<String, Object> result = callRegistry.get("desiredstate:dispatch")
            .dispatch("wf-1", Map.of(
                "executionId", "exec-1",
                "nodeId", "app",
                "nodeType", "service",
                "action", "PROVISION"))
            .get();

        assertThat(result).containsEntry("status", "SUCCESS");
        assertThat(result).containsEntry("nodeId", "app");
        assertThat(result).containsEntry("action", "PROVISION");
        assertThat(capturedProvisionContext).isNotNull();
        assertThat(capturedProvisionContext.hasApproval()).isFalse();
    }

    @Test
    void provisionPending_recordsPending() throws Exception {
        provisioner = new NodeProvisioner() {
            @Override
            public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
                return new ProvisionResult.PendingApproval(node.id(), "plan-ref-1");
            }
            @Override
            public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
                return new DeprovisionResult.Success();
            }
            @Override
            public Set<NodeType> handledTypes() {
                return Set.of(NodeType.of("service"));
            }
        };
        // Re-register overwrites — create fresh registry
        CallableDispatchRegistry freshRegistry = new CallableDispatchRegistry();
        NodeProvisionerRouter router = new DefaultNodeProvisionerRouter(List.of(provisioner));
        DesiredStateDispatch dispatch = new DesiredStateDispatch(
            router, approvalHandler, executionRegistry, freshRegistry);
        dispatch.register();

        Map<String, Object> result = freshRegistry.get("desiredstate:dispatch")
            .dispatch("wf-1", Map.of(
                "executionId", "exec-1",
                "nodeId", "app",
                "nodeType", "service",
                "action", "PROVISION"))
            .get();

        assertThat(result).containsEntry("status", "PENDING_APPROVAL");
        assertThat(result).containsEntry("planReference", "plan-ref-1");
        assertThat(approvalHandler.recorded).hasSize(1);
        assertThat(approvalHandler.recorded.get(0).planReference()).isEqualTo("plan-ref-1");
    }

    @Test
    void provisionSkipped_whenPending() throws Exception {
        approvalHandler.programCheck(
            NodeId.of("app"), StepAction.PROVISION,
            new ApprovalCheckResult.Pending("plan-ref-1"));

        Map<String, Object> result = callRegistry.get("desiredstate:dispatch")
            .dispatch("wf-1", Map.of(
                "executionId", "exec-1",
                "nodeId", "app",
                "nodeType", "service",
                "action", "PROVISION"))
            .get();

        assertThat(result).containsEntry("status", "SKIPPED");
        assertThat((String) result.get("reason")).contains("pending approval");
    }

    @Test
    void provisionWithApproval_passesContext() throws Exception {
        PlanApproval approval = new PlanApproval("plan-ref-1", "admin", Instant.now());
        approvalHandler.programCheck(
            NodeId.of("app"), StepAction.PROVISION,
            new ApprovalCheckResult.Approved(approval));

        Map<String, Object> result = callRegistry.get("desiredstate:dispatch")
            .dispatch("wf-1", Map.of(
                "executionId", "exec-1",
                "nodeId", "app",
                "nodeType", "service",
                "action", "PROVISION"))
            .get();

        assertThat(result).containsEntry("status", "SUCCESS");
        assertThat(capturedProvisionContext.hasApproval()).isTrue();
        assertThat(capturedProvisionContext.approval().planReference())
            .isEqualTo("plan-ref-1");
    }

    @Test
    void provisionRejected_acknowledgesAndReturnsRejected() throws Exception {
        approvalHandler.programCheck(
            NodeId.of("app"), StepAction.PROVISION,
            new ApprovalCheckResult.Rejected("plan-ref-1", "not authorized"));

        Map<String, Object> result = callRegistry.get("desiredstate:dispatch")
            .dispatch("wf-1", Map.of(
                "executionId", "exec-1",
                "nodeId", "app",
                "nodeType", "service",
                "action", "PROVISION"))
            .get();

        assertThat(result).containsEntry("status", "REJECTED");
        assertThat((String) result.get("reason")).contains("not authorized");
        assertThat(approvalHandler.acknowledgedRejections).hasSize(1);
    }

    @Test
    void provisionFailed_returnsFailure() throws Exception {
        provisioner = new NodeProvisioner() {
            @Override
            public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
                return new ProvisionResult.Failed("out of resources");
            }
            @Override
            public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
                return new DeprovisionResult.Success();
            }
            @Override
            public Set<NodeType> handledTypes() {
                return Set.of(NodeType.of("service"));
            }
        };
        CallableDispatchRegistry freshRegistry = new CallableDispatchRegistry();
        NodeProvisionerRouter router = new DefaultNodeProvisionerRouter(List.of(provisioner));
        new DesiredStateDispatch(router, approvalHandler, executionRegistry, freshRegistry)
            .register();

        Map<String, Object> result = freshRegistry.get("desiredstate:dispatch")
            .dispatch("wf-1", Map.of(
                "executionId", "exec-1",
                "nodeId", "app",
                "nodeType", "service",
                "action", "PROVISION"))
            .get();

        assertThat(result).containsEntry("status", "FAILED");
        assertThat(result).containsEntry("reason", "out of resources");
    }

    @Test
    void deprovisionSuccess() throws Exception {
        Map<String, Object> result = callRegistry.get("desiredstate:dispatch")
            .dispatch("wf-1", Map.of(
                "executionId", "exec-1",
                "nodeId", "app",
                "nodeType", "service",
                "action", "DEPROVISION"))
            .get();

        assertThat(result).containsEntry("status", "SUCCESS");
        assertThat(result).containsEntry("action", "DEPROVISION");
        assertThat(capturedDeprovisionContext).isNotNull();
        assertThat(capturedDeprovisionContext.hasApproval()).isFalse();
    }

    @Test
    void deprovisionWithApproval_passesContext() throws Exception {
        PlanApproval approval = new PlanApproval("plan-ref-2", "admin", Instant.now());
        approvalHandler.programCheck(
            NodeId.of("app"), StepAction.DEPROVISION,
            new ApprovalCheckResult.Approved(approval));

        Map<String, Object> result = callRegistry.get("desiredstate:dispatch")
            .dispatch("wf-1", Map.of(
                "executionId", "exec-1",
                "nodeId", "app",
                "nodeType", "service",
                "action", "DEPROVISION"))
            .get();

        assertThat(result).containsEntry("status", "SUCCESS");
        assertThat(capturedDeprovisionContext.hasApproval()).isTrue();
        assertThat(capturedDeprovisionContext.approval().planReference())
            .isEqualTo("plan-ref-2");
    }

    @Test
    void unknownNodeThrows() {
        CompletableFuture<Map<String, Object>> future =
            callRegistry.get("desiredstate:dispatch")
                .dispatch("wf-1", Map.of(
                    "executionId", "exec-1",
                    "nodeId", "nonexistent",
                    "nodeType", "vm",
                    "action", "PROVISION"));

        assertThat(future).isCompletedExceptionally();
    }

    private record TestSpec() implements NodeSpec {}
}
