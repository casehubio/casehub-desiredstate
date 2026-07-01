package io.casehub.desiredstate.api;

import java.util.Objects;

/**
 * A node in the desired-state graph: what should exist, its type, its specification,
 * and whether provisioning requires human approval.
 */
public record DesiredNode(NodeId id, NodeType type, NodeSpec spec, boolean requiresHuman) {

    public DesiredNode {
        Objects.requireNonNull(id, "DesiredNode id must not be null");
        Objects.requireNonNull(type, "DesiredNode type must not be null");
        Objects.requireNonNull(spec, "DesiredNode spec must not be null");
    }

    @Override
    public boolean requiresHuman() {
        return requiresHuman || spec.requiresHuman();
    }
}
