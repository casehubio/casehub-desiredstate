package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.NodeId;

import java.util.List;

public class GraphViolationException extends RuntimeException {

    private final List<NodeId> affectedNodes;

    public GraphViolationException(String message) {
        super(message);
        this.affectedNodes = List.of();
    }

    public GraphViolationException(String message, NodeId... nodes) {
        super(message);
        this.affectedNodes = List.of(nodes);
    }

    public List<NodeId> affectedNodes() {
        return affectedNodes;
    }
}
