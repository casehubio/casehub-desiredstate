package io.casehub.desiredstate.api;

public sealed interface GraphMutation {
    record AddNode(DesiredNode node) implements GraphMutation {}
    record RemoveNode(NodeId id) implements GraphMutation {}

    record UpdateNode(NodeId id, DesiredNode adaptedNode) implements GraphMutation {}
    record AddDependency(Dependency dependency) implements GraphMutation {}
    record RemoveDependency(Dependency dependency) implements GraphMutation {}
}
