package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ApprovalCheckResult;
import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HookDescriptor;
import io.casehub.desiredstate.api.HumanNodeHandler;
import io.casehub.desiredstate.api.LifecycleStep;
import io.casehub.desiredstate.api.LifecycleStepExecutor;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeProvisionerRouter;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.PendingApprovalHandler;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionExecutor;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.api.TransitionResult;
import org.jboss.logging.Logger;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.quarkus.arc.DefaultBean;
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

    private static final Logger LOG = Logger.getLogger(SimpleTransitionExecutor.class);
    private static final String INSTRUMENTATION_NAME = "io.casehub.desiredstate";

    private final NodeProvisionerRouter router;
    private final HumanNodeHandler humanNodeHandler;
    private final PendingApprovalHandler pendingApprovalHandler;
    private final LifecycleStepExecutor lifecycleStepExecutor;

    public SimpleTransitionExecutor(NodeProvisionerRouter router,
                                     HumanNodeHandler humanNodeHandler,
                                     PendingApprovalHandler pendingApprovalHandler,
                                     LifecycleStepExecutor lifecycleStepExecutor) {
        this.router = router;
        this.humanNodeHandler = humanNodeHandler;
        this.pendingApprovalHandler = pendingApprovalHandler;
        this.lifecycleStepExecutor = lifecycleStepExecutor;
    }

    @Override
    public TransitionResult execute(TransitionPlan plan, String tenancyId) {
        Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();

        for (OrderedStep step : plan.removals()) {
            StepOutcome outcome = executeDeprovision(step.node(), plan.before(), tenancyId);
            outcomes.put(step.node().id(), outcome);
        }

        for (OrderedStep step : plan.additions()) {
            StepOutcome outcome = executeProvision(step.node(), plan.after(), tenancyId);
            outcomes.put(step.node().id(), outcome);
        }

        return new TransitionResult(outcomes);
    }

    private StepOutcome executeProvision(DesiredNode node, DesiredStateGraph graph, String tenancyId) {
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
                case ProvisionResult.Success ignored -> {
                    if (node.hooks() != null) {
                        for (LifecycleStep step : node.hooks().provisionPost()) {
                            StepOutcome hookResult = lifecycleStepExecutor.execute(step, tenancyId);
                            if (hookResult instanceof StepOutcome.Failed f) {
                                LOG.warnf("post-provision hook failed for %s: %s", node.id().value(), f.reason());
                            }
                        }
                    }
                    yield new StepOutcome.Succeeded();
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

    private StepOutcome executeDeprovision(DesiredNode node, DesiredStateGraph graph, String tenancyId) {
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
                                LOG.warnf("post-deprovision hook failed for %s: %s", node.id().value(), f.reason());
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

}
