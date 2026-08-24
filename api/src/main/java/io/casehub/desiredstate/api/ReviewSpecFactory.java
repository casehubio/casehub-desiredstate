package io.casehub.desiredstate.api;

@FunctionalInterface
public interface ReviewSpecFactory {
    NodeSpec create(FaultEvent event, DesiredStateGraph current);

    default NodeType nodeType() {
        return create(FaultEvent.probe(), null).nodeType();
    }
}
