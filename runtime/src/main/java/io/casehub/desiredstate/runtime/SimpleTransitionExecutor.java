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
 * Executes removals first, then additions, calling the NodeProvisioner for each step.
 * Skips human nodes with a StepOutcome.Skipped result.
 */
@DefaultBean
@ApplicationScoped
public class SimpleTransitionExecutor implements TransitionExecutor {

    private static final String DEFAULT_TENANCY = "default";
    private static final String INSTRUMENTATION_NAME = "io.casehub.desiredstate";

    private final NodeProvisioner provisioner;

    public SimpleTransitionExecutor(NodeProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @Override
    public Uni<TransitionResult> execute(TransitionPlan plan) {
        return Uni.createFrom().item(() -> {
            Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();

            // Execute removals first
            for (OrderedStep step : plan.removals()) {
                StepOutcome outcome = executeDeprovision(step.node(), plan.before());
                outcomes.put(step.node().id(), outcome);
            }

            // Then execute additions
            for (OrderedStep step : plan.additions()) {
                StepOutcome outcome = executeProvision(step.node(), plan.after());
                outcomes.put(step.node().id(), outcome);
            }

            return new TransitionResult(outcomes);
        });
    }

    private StepOutcome executeProvision(DesiredNode node, DesiredStateGraph graph) {
        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("provision")
                .setAttribute(AttributeKey.stringKey("desiredstate.node.id"), node.id().value())
                .setAttribute(AttributeKey.stringKey("desiredstate.node.type"), node.type().value())
                .setAttribute(AttributeKey.booleanKey("desiredstate.requires.human"), node.requiresHuman())
                .startSpan();
        try (Scope scope = span.makeCurrent()) {
            if (node.requiresHuman()) {
                return new StepOutcome.Skipped("requires human");
            }

            ProvisionContext context = new ProvisionContext(DEFAULT_TENANCY, graph);
            ProvisionResult result = provisioner.provision(node, context);

            return switch (result) {
                case ProvisionResult.Success ignored -> new StepOutcome.Succeeded();
                case ProvisionResult.Failed f -> {
                    span.setStatus(StatusCode.ERROR, f.reason());
                    yield new StepOutcome.Failed(f.reason());
                }
            };
        } finally {
            span.end();
        }
    }

    private StepOutcome executeDeprovision(DesiredNode node, DesiredStateGraph graph) {
        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("deprovision")
                .setAttribute(AttributeKey.stringKey("desiredstate.node.id"), node.id().value())
                .setAttribute(AttributeKey.stringKey("desiredstate.node.type"), node.type().value())
                .startSpan();
        try (Scope scope = span.makeCurrent()) {
            DeprovisionContext context = new DeprovisionContext(DEFAULT_TENANCY, graph);
            DeprovisionResult result = provisioner.deprovision(node, context);

            return switch (result) {
                case DeprovisionResult.Success ignored -> new StepOutcome.Succeeded();
                case DeprovisionResult.Failed f -> {
                    span.setStatus(StatusCode.ERROR, f.reason());
                    yield new StepOutcome.Failed(f.reason());
                }
            };
        } finally {
            span.end();
        }
    }
}
