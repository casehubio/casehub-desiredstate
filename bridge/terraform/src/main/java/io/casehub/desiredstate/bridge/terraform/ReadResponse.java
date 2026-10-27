package io.casehub.desiredstate.bridge.terraform;

import java.util.List;
import java.util.Map;

public record ReadResponse(
    Map<String, Object> currentState,
    byte[] newPrivate,
    List<TfDiagnostic> diagnostics
) {}
