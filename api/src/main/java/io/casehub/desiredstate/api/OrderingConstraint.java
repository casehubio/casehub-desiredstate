package io.casehub.desiredstate.api;

import java.util.Objects;

public record OrderingConstraint(NodeType before, NodeType after) {
    public OrderingConstraint {
        Objects.requireNonNull(before, "OrderingConstraint.before must not be null");
        Objects.requireNonNull(after, "OrderingConstraint.after must not be null");
        if (before.equals(after)) {
            throw new IllegalArgumentException("Self-referencing ordering constraint: " + before);
        }
    }
}
