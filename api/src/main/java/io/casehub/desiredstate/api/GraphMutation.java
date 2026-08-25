package io.casehub.desiredstate.api;

public sealed interface GraphMutation {
    default NodeId targetNodeId() {
        return switch (this) {
            case AddNode m -> m.node().id();
            case RemoveNode m -> m.id();
            case UpdateNode m -> m.id();
            case AddDependency ignored -> null;
            case RemoveDependency ignored -> null;
        };
    }

    record AddNode(DesiredNode node) implements GraphMutation {}
    record RemoveNode(NodeId id) implements GraphMutation {}

    record UpdateNode(NodeId id, DesiredNode adaptedNode) implements GraphMutation {}
    record AddDependency(Dependency dependency) implements GraphMutation {}
    record RemoveDependency(Dependency dependency) implements GraphMutation {}
}
