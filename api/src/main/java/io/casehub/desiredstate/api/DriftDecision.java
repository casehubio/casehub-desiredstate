package io.casehub.desiredstate.api;

import java.util.Objects;

public sealed interface DriftDecision {
    record Reconcile() implements DriftDecision {}
    record Exempt(ExemptionSpec spec) implements DriftDecision {
        public Exempt { Objects.requireNonNull(spec, "spec"); }
    }

    static DriftDecision reconcile() { return new Reconcile(); }
    static DriftDecision exempt(ExemptionSpec spec) { return new Exempt(spec); }
}
