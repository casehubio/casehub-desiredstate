package io.casehub.desiredstate.api;

import java.util.Objects;

public record DesiredNode(NodeId id, NodeType type, NodeSpec spec, HumanGating humanGating) {

    public DesiredNode {
        Objects.requireNonNull(id, "DesiredNode id must not be null");
        Objects.requireNonNull(type, "DesiredNode type must not be null");
        Objects.requireNonNull(spec, "DesiredNode spec must not be null");
        Objects.requireNonNull(humanGating, "DesiredNode humanGating must not be null");
    }

    public boolean requiresHuman(StepAction action) {
        return humanGating.requiresHuman(action) || spec.humanGating().requiresHuman(action);
    }

    public boolean requiresHuman() {
        return humanGating.any() || spec.humanGating().any();
    }
}
