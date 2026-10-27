package io.casehub.desiredstate.bridge.terraform;

import io.casehub.desiredstate.bridge.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class TerraformBridgeProvider implements BridgeProvider, PlanCapable {

    private final ProviderProcessManager processManager;
    private Set<String> supportedTypes;

    public TerraformBridgeProvider(ProviderProcessManager processManager) {
        this.processManager = processManager;
    }

    @Override
    public String protocolId() {
        return "terraform";
    }

    @Override
    public Set<String> supportedTypes() {
        if (supportedTypes == null) {
            supportedTypes = processManager.discoverTypes().stream()
                .map(dt -> dt.nodeType().value())
                .collect(Collectors.toSet());
        }
        return supportedTypes;
    }

    @Override
    public BridgePlan plan(BridgeRequest request) {
        var protocol = processManager.getProtocol();
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
    }

    @Override
    public BridgeResult applyPlan(BridgePlan plan) {
        var protocol = processManager.getProtocol();
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
        var protocol = processManager.getProtocol();
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

    @Override
    public BridgeResult destroy(BridgeRequest request) {
        var protocol = processManager.getProtocol();
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

    @Override
    public BridgeStateResult readState(BridgeReadRequest request) {
        var protocol = processManager.getProtocol();
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
