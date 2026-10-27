package io.casehub.desiredstate.bridge.terraform;

import java.util.List;
import java.util.Map;

public record ApplyResponse(
    Map<String, Object> newState,
    byte[] newPrivate,
    List<TfDiagnostic> diagnostics
) {}
