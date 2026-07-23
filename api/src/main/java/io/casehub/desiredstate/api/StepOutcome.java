package io.casehub.desiredstate.api;

public sealed interface StepOutcome {
    record Succeeded() implements StepOutcome {}

    record Failed(String reason) implements StepOutcome {
        public Failed {java.util.Objects.requireNonNull(reason, "StepOutcome.Failed.reason must not be null");}
    }

    record Skipped(String reason) implements StepOutcome {
        public Skipped {java.util.Objects.requireNonNull(reason, "StepOutcome.Skipped.reason must not be null");}
    }

    record Rejected(String reason) implements StepOutcome {
        public Rejected {java.util.Objects.requireNonNull(reason, "StepOutcome.Rejected.reason must not be null");}
    }
}
