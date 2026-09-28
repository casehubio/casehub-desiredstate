package io.casehub.desiredstate.api;

import java.util.Objects;

public record DesiredNode(NodeId id, NodeSpec spec, HumanGating humanGating, HookDescriptor hooks,
                          TargetStatus targetStatus) {

    public DesiredNode(NodeId id, NodeSpec spec, HumanGating humanGating) {
        this(id, spec, humanGating, null, TargetStatus.ACTIVE);
    }

    public DesiredNode(NodeId id, NodeSpec spec, HumanGating humanGating, HookDescriptor hooks) {
        this(id, spec, humanGating, hooks, TargetStatus.ACTIVE);
    }

    public DesiredNode {
        Objects.requireNonNull(id, "DesiredNode id must not be null");
        Objects.requireNonNull(spec, "DesiredNode spec must not be null");
        Objects.requireNonNull(humanGating, "DesiredNode humanGating must not be null");
        Objects.requireNonNull(targetStatus, "DesiredNode targetStatus must not be null");
    }

    public NodeType type() {
        return spec.nodeType();
    }

    public boolean requiresHuman(StepAction action) {
        return humanGating.requiresHuman(action) || spec.humanGating().requiresHuman(action);
    }

    public boolean requiresHuman() {
        return humanGating.any() || spec.humanGating().any();
    }
}
