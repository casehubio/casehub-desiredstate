package io.casehub.desiredstate.bridge.terraform;

import java.util.List;
import java.util.Map;

public record ImportResponse(
    Map<String, Object> state,
    byte[] privateData,
    List<TfDiagnostic> diagnostics
) {}
