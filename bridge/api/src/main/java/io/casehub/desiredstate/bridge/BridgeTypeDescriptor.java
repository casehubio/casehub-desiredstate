package io.casehub.desiredstate.bridge;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import java.util.Map;

public record BridgeTypeDescriptor(
    NodeType nodeType,
    Map<String, NodeStatus> stateMapping,
    NodeStatus defaultState,
    NodeStatus notFoundState,
    Map<String, BridgeErrorCategory> errorMapping,
    BridgeErrorCategory defaultErrorCategory
) {
    public BridgeTypeDescriptor {
        if (defaultState == null) defaultState = NodeStatus.DRIFTED;
        if (notFoundState == null) notFoundState = NodeStatus.ABSENT;
        if (defaultErrorCategory == null) defaultErrorCategory = BridgeErrorCategory.PERMANENT;
    }
}
