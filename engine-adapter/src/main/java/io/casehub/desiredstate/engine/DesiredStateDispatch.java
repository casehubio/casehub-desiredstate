package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.*;
import io.casehub.engine.flow.CallableDispatchRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class DesiredStateDispatch {

    private final NodeProvisionerRouter router;
    private final PendingApprovalHandler pendingApprovalHandler;
    private final DesiredStateExecutionRegistry executionRegistry;
    private final CallableDispatchRegistry callRegistry;

    @Inject
    public DesiredStateDispatch(NodeProvisionerRouter router,
                                 PendingApprovalHandler pendingApprovalHandler,
                                 DesiredStateExecutionRegistry executionRegistry,
                                 CallableDispatchRegistry callRegistry) {
        this.router = router;
        this.pendingApprovalHandler = pendingApprovalHandler;
        this.executionRegistry = executionRegistry;
        this.callRegistry = callRegistry;
    }

    void register() {
        callRegistry.register("desiredstate:dispatch", this::dispatch);
    }

    @jakarta.annotation.PostConstruct
    void init() {
        register();
    }

    CompletableFuture<Map<String, Object>> dispatch(
            String workflowInstanceId, Map<String, Object> args) {
        try {
            String executionId = requireString(args, "executionId");
            String nodeIdStr = requireString(args, "nodeId");
            String actionStr = requireString(args, "action");

            DesiredStateExecutionContext ctx = executionRegistry.get(executionId);
            NodeId nodeId = NodeId.of(nodeIdStr);
            StepAction action = StepAction.valueOf(actionStr);

            DesiredNode node = ctx.graph().nodes().get(nodeId);
            if (node == null) {
                throw new IllegalArgumentException(
                    "Node not found in graph: " + nodeIdStr);
            }

            Map<String, Object> result = switch (action) {
                case PROVISION -> executeProvision(node, ctx);
                case DEPROVISION -> executeDeprovision(node, ctx);
            };

            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private Map<String, Object> executeProvision(DesiredNode node,
                                                   DesiredStateExecutionContext ctx) {
        ProvisionContext context = new ProvisionContext(ctx.tenancyId(), ctx.graph());

        ApprovalCheckResult approvalCheck = pendingApprovalHandler.check(
            node, StepAction.PROVISION, ctx.tenancyId());
        switch (approvalCheck) {
            case ApprovalCheckResult.Pending p ->
                { return resultMap(node, "PROVISION", "SKIPPED",
                    "pending approval: " + p.planReference(), null); }
            case ApprovalCheckResult.Rejected r -> {
                pendingApprovalHandler.acknowledgeRejection(
                    node, StepAction.PROVISION, ctx.tenancyId());
                return resultMap(node, "PROVISION", "REJECTED",
                    "approval rejected: " + r.reason(), null);
            }
            case ApprovalCheckResult.Approved a ->
                context = context.withApproval(a.approval());
            case ApprovalCheckResult.None ignored -> {}
        }

        ProvisionResult result = router.provision(node, context);

        return switch (result) {
            case ProvisionResult.Success ignored ->
                resultMap(node, "PROVISION", "SUCCESS", null, null);
            case ProvisionResult.Failed f ->
                resultMap(node, "PROVISION", "FAILED", f.reason(), null);
            case ProvisionResult.PendingApproval pa -> {
                pendingApprovalHandler.recordPending(
                    node, StepAction.PROVISION, ctx.tenancyId(), pa.planReference());
                yield resultMap(node, "PROVISION", "PENDING_APPROVAL",
                    null, pa.planReference());
            }
        };
    }

    private Map<String, Object> executeDeprovision(DesiredNode node,
                                                     DesiredStateExecutionContext ctx) {
        DeprovisionContext context = new DeprovisionContext(ctx.tenancyId(), ctx.graph());

        ApprovalCheckResult approvalCheck = pendingApprovalHandler.check(
            node, StepAction.DEPROVISION, ctx.tenancyId());
        switch (approvalCheck) {
            case ApprovalCheckResult.Pending p ->
                { return resultMap(node, "DEPROVISION", "SKIPPED",
                    "pending approval: " + p.planReference(), null); }
            case ApprovalCheckResult.Rejected r -> {
                pendingApprovalHandler.acknowledgeRejection(
                    node, StepAction.DEPROVISION, ctx.tenancyId());
                return resultMap(node, "DEPROVISION", "REJECTED",
                    "approval rejected: " + r.reason(), null);
            }
            case ApprovalCheckResult.Approved a ->
                context = context.withApproval(a.approval());
            case ApprovalCheckResult.None ignored -> {}
        }

        DeprovisionResult result = router.deprovision(node, context);

        return switch (result) {
            case DeprovisionResult.Success ignored ->
                resultMap(node, "DEPROVISION", "SUCCESS", null, null);
            case DeprovisionResult.Failed f ->
                resultMap(node, "DEPROVISION", "FAILED", f.reason(), null);
            case DeprovisionResult.PendingApproval pa -> {
                pendingApprovalHandler.recordPending(
                    node, StepAction.DEPROVISION, ctx.tenancyId(), pa.planReference());
                yield resultMap(node, "DEPROVISION", "PENDING_APPROVAL",
                    null, pa.planReference());
            }
        };
    }

    private static Map<String, Object> resultMap(DesiredNode node, String action,
                                                   String status, String reason,
                                                   String planReference) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeId", node.id().value());
        map.put("action", action);
        map.put("status", status);
        if (reason != null) map.put("reason", reason);
        if (planReference != null) map.put("planReference", planReference);
        return map;
    }

    private static String requireString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required arg: " + key);
        }
        return value.toString();
    }
}
