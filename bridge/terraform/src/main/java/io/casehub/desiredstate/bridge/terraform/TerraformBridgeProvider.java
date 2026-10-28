package io.casehub.desiredstate.bridge.terraform;

import io.casehub.desiredstate.bridge.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class TerraformBridgeProvider implements BridgeProvider, PlanCapable {

    private final TenantProviderRegistry registry;
    private Set<String> supportedTypes;

    public TerraformBridgeProvider(TenantProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String protocolId() {
        return "terraform";
    }

    @Override
    public Set<String> supportedTypes() {
        if (supportedTypes == null) {
            supportedTypes = registry.discoverTypes().stream()
                .map(dt -> dt.nodeType().value())
                .collect(Collectors.toSet());
        }
        return supportedTypes;
    }

    @Override
    public BridgePlan plan(BridgeRequest request) {
        return withAuthRetry(request.tenancyId(), () -> {
            var protocol = registry.getProtocol(request.tenancyId());
            var response = protocol.plan(
                stripNamespace(request.typeId()),
                request.previousState(),
                request.spec());
            checkDiagnostics(response.diagnostics());
            return new BridgePlan(
                request.resourceId(),
                "Plan for " + request.typeId(),
                List.of(new ResourceChange(request.typeId(), request.resourceId(), ChangeAction.CREATE)),
                response.plannedPrivate());
        });
    }

    @Override
    public BridgeResult applyPlan(BridgePlan plan) {
        var protocol = registry.getProtocol(plan.planId());
        var response = protocol.apply(
            stripNamespace(plan.planId()),
            Map.of(),
            (byte[]) plan.rawPlan());
        if (hasErrors(response.diagnostics())) {
            return new BridgeResult.Failed(
                formatErrors(response.diagnostics()),
                TerraformErrorClassifier.classify(response.diagnostics()));
        }
        return new BridgeResult.Success(response.newState());
    }

    @Override
    public BridgeResult apply(BridgeRequest request) {
        return withAuthRetry(request.tenancyId(), () -> doApply(request));
    }

    @Override
    public BridgeResult destroy(BridgeRequest request) {
        return withAuthRetry(request.tenancyId(), () -> doDestroy(request));
    }

    @Override
    public BridgeStateResult readState(BridgeReadRequest request) {
        return withAuthRetry(request.tenancyId(), () -> doReadState(request));
    }

    private BridgeResult doApply(BridgeRequest request) {
        var protocol = registry.getProtocol(request.tenancyId());
        var planResp = protocol.plan(
            stripNamespace(request.typeId()),
            request.previousState(),
            request.spec());
        if (hasErrors(planResp.diagnostics())) {
            return new BridgeResult.Failed(
                formatErrors(planResp.diagnostics()),
                TerraformErrorClassifier.classify(planResp.diagnostics()));
        }
        var applyResp = protocol.apply(
            stripNamespace(request.typeId()),
            request.previousState(),
            planResp.plannedPrivate());
        if (hasErrors(applyResp.diagnostics())) {
            return new BridgeResult.Failed(
                formatErrors(applyResp.diagnostics()),
                TerraformErrorClassifier.classify(applyResp.diagnostics()));
        }
        return new BridgeResult.Success(applyResp.newState());
    }

    private BridgeResult doDestroy(BridgeRequest request) {
        var protocol = registry.getProtocol(request.tenancyId());
        var planResp = protocol.plan(
            stripNamespace(request.typeId()),
            request.previousState(),
            null);
        if (hasErrors(planResp.diagnostics())) {
            return new BridgeResult.Failed(
                formatErrors(planResp.diagnostics()),
                TerraformErrorClassifier.classify(planResp.diagnostics()));
        }
        var applyResp = protocol.apply(
            stripNamespace(request.typeId()),
            request.previousState(),
            planResp.plannedPrivate());
        if (hasErrors(applyResp.diagnostics())) {
            return new BridgeResult.Failed(
                formatErrors(applyResp.diagnostics()),
                TerraformErrorClassifier.classify(applyResp.diagnostics()));
        }
        return new BridgeResult.Success(Map.of());
    }

    private BridgeStateResult doReadState(BridgeReadRequest request) {
        var protocol = registry.getProtocol(request.tenancyId());
        var response = protocol.read(
            stripNamespace(request.typeId()),
            request.previousState());
        if (response.currentState() == null || response.currentState().isEmpty()) {
            return new BridgeStateResult.NotFound();
        }
        var planResp = protocol.plan(
            stripNamespace(request.typeId()),
            response.currentState(),
            request.desiredSpec());
        boolean drifted = !planResp.diagnostics().isEmpty() || planResp.requiresReplace();
        Map<String, Object> state = new HashMap<>(response.currentState());
        state.put("status", drifted ? "drifted" : "present");
        return new BridgeStateResult.Found(state);
    }

    @SuppressWarnings("unchecked")
    private <T> T withAuthRetry(String tenancyId, java.util.function.Supplier<T> operation) {
        T result = operation.get();
        if (tenancyId != null && isAuthError(result)) {
            registry.evict(tenancyId);
            result = operation.get();
        }
        return result;
    }

    private boolean isAuthError(Object result) {
        if (result instanceof BridgeResult.Failed f) {
            return f.category() == BridgeErrorCategory.AUTHENTICATION;
        }
        return false;
    }

    private String stripNamespace(String typeId) {
        int lastColon = typeId.lastIndexOf(':');
        return lastColon >= 0 ? typeId.substring(lastColon + 1) : typeId;
    }

    private boolean hasErrors(List<TfDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.severity() == TfDiagnostic.Severity.ERROR);
    }

    private void checkDiagnostics(List<TfDiagnostic> diagnostics) {
        if (hasErrors(diagnostics)) {
            throw new RuntimeException("Terraform operation failed: " + formatErrors(diagnostics));
        }
    }

    private String formatErrors(List<TfDiagnostic> diagnostics) {
        return diagnostics.stream()
            .filter(d -> d.severity() == TfDiagnostic.Severity.ERROR)
            .map(d -> d.summary() + ": " + d.detail())
            .collect(Collectors.joining("; "));
    }
}
