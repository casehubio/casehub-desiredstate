package io.casehub.desiredstate.api;

public sealed interface StepOutcome {
    record Succeeded() implements StepOutcome {}
    record Failed(String reason) implements StepOutcome {}
    record Skipped(String reason) implements StepOutcome {}
    record Rejected(String reason) implements StepOutcome {}
}
