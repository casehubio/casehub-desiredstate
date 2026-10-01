package io.casehub.desiredstate.api;

import java.util.Map;
import java.util.Objects;

public record ExemptionSpec(RevertCondition revertCondition,
                            Map<String, String> metadata) {
    public ExemptionSpec {
        Objects.requireNonNull(revertCondition, "revertCondition");
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
