package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simple sequential transition executor.
 * Executes removals first, then additions, calling the NodeProvisionerRouter for each step.
 * Delegates requiresHuman nodes to the HumanNodeHandler.
 * Wraps provisioner calls with PendingApprovalHandler for approval lifecycle management.
 */
@DefaultBean
@ApplicationScoped
public class SimpleTransitionExecutor implements TransitionExecutor {

    private static final String INSTRUMENTATION_NAME = "io.casehub.desiredstate";

    private final NodeProvisionerRouter router;
    private final HumanNodeHandler humanNodeHandler;
    private final PendingApprovalHandler pendingApprovalHandler;

    public SimpleTransitionExecutor(NodeProvisionerRouter router,
                                     HumanNodeHandler humanNodeHandler,
                                     PendingApprovalHandler pendingApprovalHandler) {
        this.router = router;
        this.humanNodeHandler = humanNodeHandler;
        this.pendingApprovalHandler = pendingApprovalHandler;
    }

    @Override
    public Uni<TransitionResult> execute(TransitionPlan plan, String tenancyId) {
        return Uni.createFrom().item(() -> {
            Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();

            // Execute removals first
            for (OrderedStep step : plan.removals()) {
                StepOutcome outcome = executeDeprovision(step.node(), plan.before(), tenancyId);
                outcomes.put(step.node().id(), outcome);
            }

            // Then execute additions
            for (OrderedStep step : plan.additions()) {
                StepOutcome outcome = executeProvision(step.node(), plan.after(), tenancyId);
                outcomes.put(step.node().id(), outcome);
            }

            return new TransitionResult(outcomes);
        });
    }

    private StepOutcome executeProvision(DesiredNode node, DesiredStateGraph graph, String tenancyId) {
        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("provision")
                .setAttribute(AttributeKey.stringKey("desiredstate.node.id"), node.id().value())
                .setAttribute(AttributeKey.stringKey("desiredstate.node.type"), node.type().value())
                .setAttribute(AttributeKey.booleanKey("desiredstate.requires.human"), node.requiresHuman())
                .startSpan();
        try (Scope scope = span.makeCurrent()) {
            ProvisionContext context = new ProvisionContext(tenancyId, graph);

            // requiresHuman takes precedence — delegates entirely to HumanNodeHandler
            if (node.requiresHuman()) {
                return humanNodeHandler.onProvision(node, context);
            }

            // Check for prior approval state before calling provisioner
            ApprovalCheckResult approvalCheck = pendingApprovalHandler.check(node, StepAction.PROVISION, tenancyId);
            switch (approvalCheck) {
                case ApprovalCheckResult.Pending p ->
                    { return new StepOutcome.Skipped("pending approval: " + p.planReference()); }
                case ApprovalCheckResult.Rejected r -> {
                    pendingApprovalHandler.acknowledgeRejection(node, StepAction.PROVISION, tenancyId);
                    span.setStatus(StatusCode.ERROR, "approval rejected: " + r.reason());
                    return new StepOutcome.Rejected("approval rejected: " + r.reason());
                }
                case ApprovalCheckResult.Approved a ->
                    context = context.withApproval(a.approval());
                case ApprovalCheckResult.None ignored -> {}
            }

            ProvisionResult result = router.provision(node, context);

            return switch (result) {
                case ProvisionResult.Success ignored -> new StepOutcome.Succeeded();
                case ProvisionResult.Failed f -> {
                    span.setStatus(StatusCode.ERROR, f.reason());
                    yield new StepOutcome.Failed(f.reason());
                }
                case ProvisionResult.PendingApproval pa ->
                    pendingApprovalHandler.recordPending(node, StepAction.PROVISION, tenancyId, pa.planReference());
            };
        } finally {
            span.end();
        }
    }

    private StepOutcome executeDeprovision(DesiredNode node, DesiredStateGraph graph, String tenancyId) {
        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("deprovision")
                .setAttribute(AttributeKey.stringKey("desiredstate.node.id"), node.id().value())
                .setAttribute(AttributeKey.stringKey("desiredstate.node.type"), node.type().value())
                .startSpan();
        try (Scope scope = span.makeCurrent()) {
            DeprovisionContext context = new DeprovisionContext(tenancyId, graph);

            // Check for prior approval state before calling provisioner (no requiresHuman for deprovision)
            ApprovalCheckResult approvalCheck = pendingApprovalHandler.check(node, StepAction.DEPROVISION, tenancyId);
            switch (approvalCheck) {
                case ApprovalCheckResult.Pending p ->
                    { return new StepOutcome.Skipped("pending approval: " + p.planReference()); }
                case ApprovalCheckResult.Rejected r -> {
                    pendingApprovalHandler.acknowledgeRejection(node, StepAction.DEPROVISION, tenancyId);
                    span.setStatus(StatusCode.ERROR, "approval rejected: " + r.reason());
                    return new StepOutcome.Rejected("approval rejected: " + r.reason());
                }
                case ApprovalCheckResult.Approved a ->
                    context = context.withApproval(a.approval());
                case ApprovalCheckResult.None ignored -> {}
            }

            DeprovisionResult result = router.deprovision(node, context);

            return switch (result) {
                case DeprovisionResult.Success ignored -> new StepOutcome.Succeeded();
                case DeprovisionResult.Failed f -> {
                    span.setStatus(StatusCode.ERROR, f.reason());
                    yield new StepOutcome.Failed(f.reason());
                }
                case DeprovisionResult.PendingApproval pa ->
                    pendingApprovalHandler.recordPending(node, StepAction.DEPROVISION, tenancyId, pa.planReference());
            };
        } finally {
            span.end();
        }
    }
}
