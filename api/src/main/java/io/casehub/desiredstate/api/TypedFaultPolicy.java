package io.casehub.desiredstate.api;

import java.util.List;

public interface TypedFaultPolicy extends FaultPolicy {

    NodeType outputNodeType();

    static TypedFaultPolicy of(NodeType nodeType, FaultPolicy delegate) {
        return new TypedFaultPolicy() {
            @Override public NodeType outputNodeType() { return nodeType; }
            @Override public List<GraphMutation> onFault(String tenancyId, FaultEvent event,
                    DesiredStateGraph current, ActualState actual) {
                return delegate.onFault(tenancyId, event, current, actual);
            }
        };
    }
}