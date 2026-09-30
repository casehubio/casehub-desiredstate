package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ApprovalCheckResult;
import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanNodeHandler;
import io.casehub.desiredstate.api.LifecycleStep;
import io.casehub.desiredstate.api.LifecycleStepExecutor;
import io.casehub.desiredstate.api.NodeProvisionerRouter;
import io.casehub.desiredstate.api.PendingApprovalHandler;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.ResumeContext;
import io.casehub.desiredstate.api.ResumeResult;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.SuspendContext;
import io.casehub.desiredstate.api.SuspendResult;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

import java.util.logging.Logger;

public class NodeStepExecutor {

    private static final Logger LOG = Logger.getLogger(NodeStepExecutor.class.getName());
    private static final String INSTRUMENTATION_NAME = "io.casehub.desiredstate";

    private final NodeProvisionerRouter router;
    private final HumanNodeHandler humanNodeHandler;
    private final PendingApprovalHandler pendingApprovalHandler;
    private final LifecycleStepExecutor lifecycleStepExecutor;

    public NodeStepExecutor(NodeProvisionerRouter router,
                            HumanNodeHandler humanNodeHandler,
                            PendingApprovalHandler pendingApprovalHandler,
                            LifecycleStepExecutor lifecycleStepExecutor) {
        this.router = router;
        this.humanNodeHandler = humanNodeHandler;
        this.pendingApprovalHandler = pendingApprovalHandler;
        this.lifecycleStepExecutor = lifecycleStepExecutor;
    }

    public StepOutcome execute(DesiredNode node, StepAction action,
                               DesiredStateGraph graph, String tenancyId) {
        return switch (action) {
            case PROVISION -> executeProvision(node, graph, tenancyId);
            case DEPROVISION -> executeDeprovision(node, graph, tenancyId);
            case SUSPEND -> executeSuspend(node, graph, tenancyId);
            case RESUME -> executeResume(node, graph, tenancyId);
        };
    }

    StepOutcome executeProvision(DesiredNode node, DesiredStateGraph graph, String tenancyId) {
        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("provision")
                .setAttribute(AttributeKey.stringKey("desiredstate.node.id"), node.id().value())
                .setAttribute(AttributeKey.stringKey("desiredstate.node.type"), node.type().value())
                .setAttribute(AttributeKey.stringKey("desiredstate.human.gating"), node.humanGating().name())
                .setAttribute(AttributeKey.booleanKey("desiredstate.requires.human"), node.requiresHuman(StepAction.PROVISION))
                .startSpan();
        try (Scope scope = span.makeCurrent()) {
            ProvisionContext context = new ProvisionContext(tenancyId, graph);

            if (node.requiresHuman(StepAction.PROVISION)) {
                return humanNodeHandler.onProvision(node, context);
            }

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

            if (node.hooks() != null) {
                for (LifecycleStep step : node.hooks().provisionPre()) {
                    StepOutcome hookResult = lifecycleStepExecutor.execute(step, tenancyId);
                    if (hookResult instanceof StepOutcome.Failed f) {
                        span.setStatus(StatusCode.ERROR, "pre-provision hook failed: " + f.reason());
                        return new StepOutcome.Failed("pre-provision hook failed: " + f.reason());
                    }
                }
            }

            ProvisionResult result = router.provision(node, context);

            return switch (result) {
                case ProvisionResult.Success s -> {
                    runPostProvisionHooks(node, tenancyId);
                    yield new StepOutcome.Succeeded();
                }
                case ProvisionResult.AlreadyConverged a -> {
                    runPostProvisionHooks(node, tenancyId);
                    yield new StepOutcome.AlreadyConverged();
                }
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

    private void runPostProvisionHooks(DesiredNode node, String tenancyId) {
        if (node.hooks() != null) {
            for (LifecycleStep step : node.hooks().provisionPost()) {
                StepOutcome hookResult = lifecycleStepExecutor.execute(step, tenancyId);
                if (hookResult instanceof StepOutcome.Failed f) {
                    LOG.warning(String.format("post-provision hook failed for %s: %s", node.id().value(), f.reason()));
                }
            }
        }
    }


    StepOutcome executeDeprovision(DesiredNode node, DesiredStateGraph graph, String tenancyId) {
        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("deprovision")
                                       .setAttribute(AttributeKey.stringKey("desiredstate.node.id"), node.id().value())
                                       .setAttribute(AttributeKey.stringKey("desiredstate.node.type"), node.type().value())
                                       .setAttribute(AttributeKey.stringKey("desiredstate.human.gating"), node.humanGating().name())
                                       .setAttribute(AttributeKey.booleanKey("desiredstate.requires.human"), node.requiresHuman(StepAction.DEPROVISION))
                                       .startSpan();
        try (Scope scope = span.makeCurrent()) {
            DeprovisionContext context = new DeprovisionContext(tenancyId, graph);

            if (node.requiresHuman(StepAction.DEPROVISION)) {
                return humanNodeHandler.onDeprovision(node, context);
            }

            ApprovalCheckResult approvalCheck = pendingApprovalHandler.check(node, StepAction.DEPROVISION, tenancyId);
            switch (approvalCheck) {
                case ApprovalCheckResult.Pending p -> {
                    return new StepOutcome.Skipped("pending approval: " + p.planReference());
                }
                case ApprovalCheckResult.Rejected r -> {
                    pendingApprovalHandler.acknowledgeRejection(node, StepAction.DEPROVISION, tenancyId);
                    span.setStatus(StatusCode.ERROR, "approval rejected: " + r.reason());
                    return new StepOutcome.Rejected("approval rejected: " + r.reason());
                }
                case ApprovalCheckResult.Approved a -> context = context.withApproval(a.approval());
                case ApprovalCheckResult.None ignored -> {}
            }

            if (node.hooks() != null) {
                for (LifecycleStep step : node.hooks().deprovisionPre()) {
                    StepOutcome hookResult = lifecycleStepExecutor.execute(step, tenancyId);
                    if (hookResult instanceof StepOutcome.Failed f) {
                        span.setStatus(StatusCode.ERROR, "pre-deprovision hook failed: " + f.reason());
                        return new StepOutcome.Failed("pre-deprovision hook failed: " + f.reason());
                    }
                }
            }

            DeprovisionResult result = router.deprovision(node, context);

            return switch (result) {
                case DeprovisionResult.Success ignored -> {
                    if (node.hooks() != null) {
                        for (LifecycleStep step : node.hooks().deprovisionPost()) {
                            StepOutcome hookResult = lifecycleStepExecutor.execute(step, tenancyId);
                            if (hookResult instanceof StepOutcome.Failed f) {
                                LOG.warning(String.format("post-deprovision hook failed for %s: %s", node.id().value(), f.reason()));
                            }
                        }
                    }
                    yield new StepOutcome.Succeeded();
                }
                case DeprovisionResult.Failed f -> {
                    span.setStatus(StatusCode.ERROR, f.reason());
                    yield new StepOutcome.Failed(f.reason());
                }
                case DeprovisionResult.PendingApproval pa -> pendingApprovalHandler.recordPending(node, StepAction.DEPROVISION, tenancyId, pa.planReference());
            };
        } finally {
            span.end();
        }
    }

    StepOutcome executeSuspend(DesiredNode node, DesiredStateGraph graph, String tenancyId) {
        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("suspend")
                                       .setAttribute(AttributeKey.stringKey("desiredstate.node.id"), node.id().value())
                                       .setAttribute(AttributeKey.stringKey("desiredstate.node.type"), node.type().value())
                                       .setAttribute(AttributeKey.stringKey("desiredstate.human.gating"), node.humanGating().name())
                                       .setAttribute(AttributeKey.booleanKey("desiredstate.requires.human"), node.requiresHuman(StepAction.SUSPEND))
                                       .startSpan();
        try (Scope scope = span.makeCurrent()) {
            SuspendContext context = new SuspendContext(tenancyId, graph);

            if (node.requiresHuman(StepAction.SUSPEND)) {
                return humanNodeHandler.onSuspend(node, context);
            }

            ApprovalCheckResult approvalCheck = pendingApprovalHandler.check(node, StepAction.SUSPEND, tenancyId);
            switch (approvalCheck) {
                case ApprovalCheckResult.Pending p -> {
                    return new StepOutcome.Skipped("pending approval: " + p.planReference());
                }
                case ApprovalCheckResult.Rejected r -> {
                    pendingApprovalHandler.acknowledgeRejection(node, StepAction.SUSPEND, tenancyId);
                    span.setStatus(StatusCode.ERROR, "approval rejected: " + r.reason());
                    return new StepOutcome.Rejected("approval rejected: " + r.reason());
                }
                case ApprovalCheckResult.Approved a -> context = context.withApproval(a.approval());
                case ApprovalCheckResult.None ignored -> {}
            }

            SuspendResult result = router.suspend(node, context);

            return switch (result) {
                case SuspendResult.Success ignored -> new StepOutcome.Succeeded();
                case SuspendResult.Failed f -> {
                    span.setStatus(StatusCode.ERROR, f.reason());
                    yield new StepOutcome.Failed(f.reason());
                }
                case SuspendResult.PendingApproval pa -> pendingApprovalHandler.recordPending(node, StepAction.SUSPEND, tenancyId, pa.planReference());
            };
        } finally {
            span.end();
        }
    }

    StepOutcome executeResume(DesiredNode node, DesiredStateGraph graph, String tenancyId) {
        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("resume")
                                       .setAttribute(AttributeKey.stringKey("desiredstate.node.id"), node.id().value())
                                       .setAttribute(AttributeKey.stringKey("desiredstate.node.type"), node.type().value())
                                       .setAttribute(AttributeKey.stringKey("desiredstate.human.gating"), node.humanGating().name())
                                       .setAttribute(AttributeKey.booleanKey("desiredstate.requires.human"), node.requiresHuman(StepAction.RESUME))
                                       .startSpan();
        try (Scope scope = span.makeCurrent()) {
            ResumeContext context = new ResumeContext(tenancyId, graph);

            if (node.requiresHuman(StepAction.RESUME)) {
                return humanNodeHandler.onResume(node, context);
            }

            ApprovalCheckResult approvalCheck = pendingApprovalHandler.check(node, StepAction.RESUME, tenancyId);
            switch (approvalCheck) {
                case ApprovalCheckResult.Pending p -> {
                    return new StepOutcome.Skipped("pending approval: " + p.planReference());
                }
                case ApprovalCheckResult.Rejected r -> {
                    pendingApprovalHandler.acknowledgeRejection(node, StepAction.RESUME, tenancyId);
                    span.setStatus(StatusCode.ERROR, "approval rejected: " + r.reason());
                    return new StepOutcome.Rejected("approval rejected: " + r.reason());
                }
                case ApprovalCheckResult.Approved a -> context = context.withApproval(a.approval());
                case ApprovalCheckResult.None ignored -> {}
            }

            ResumeResult result = router.resume(node, context);

            return switch (result) {
                case ResumeResult.Success ignored -> new StepOutcome.Succeeded();
                case ResumeResult.Failed f -> {
                    span.setStatus(StatusCode.ERROR, f.reason());
                    yield new StepOutcome.Failed(f.reason());
                }
                case ResumeResult.PendingApproval pa -> pendingApprovalHandler.recordPending(node, StepAction.RESUME, tenancyId, pa.planReference());
            };
        } finally {
            span.end();
        }
    }
}
