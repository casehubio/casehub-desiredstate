package io.casehub.desiredstate.bridge.terraform;

public record TfDiagnostic(Severity severity, String summary, String detail) {
    public enum Severity { ERROR, WARNING }
}
