package io.casehub.desiredstate.annotations.runtime;

import java.util.List;
import java.util.stream.Collectors;

public class GraphInvariantViolationsException extends RuntimeException {

    private final List<GraphViolation> violations;

    public GraphInvariantViolationsException(List<GraphViolation> violations) {
        super("Graph invariant violations:\n" + violations.stream()
                .map(v -> "  - " + v.invariantName() + ": " + v.message())
                .collect(Collectors.joining("\n")));
        this.violations = List.copyOf(violations);
    }

    public List<GraphViolation> violations() {
        return violations;
    }
}
