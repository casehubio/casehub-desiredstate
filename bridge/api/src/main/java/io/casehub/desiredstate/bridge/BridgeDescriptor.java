package io.casehub.desiredstate.bridge;

import io.casehub.desiredstate.api.NodeType;
import java.util.Map;
import java.util.Set;

public record BridgeDescriptor(
    String protocolId,
    Set<NodeType> handledTypes,
    Map<String, BridgeTypeDescriptor> typeDescriptors,
    BridgeLifecycleConfig lifecycleConfig
) {}
