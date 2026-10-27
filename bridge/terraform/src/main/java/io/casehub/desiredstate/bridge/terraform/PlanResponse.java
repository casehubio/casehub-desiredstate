package io.casehub.desiredstate.bridge.terraform;

import java.util.List;
import java.util.Map;

public record PlanResponse(
    Map<String, Object> plannedState,
    byte[] plannedPrivate,
    boolean requiresReplace,
    List<TfDiagnostic> diagnostics
) {}
