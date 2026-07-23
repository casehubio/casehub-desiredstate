package io.casehub.desiredstate.api;

@FunctionalInterface
public interface ReviewSpecFactory {
    NodeSpec create(FaultEvent event, DesiredStateGraph current);
}
