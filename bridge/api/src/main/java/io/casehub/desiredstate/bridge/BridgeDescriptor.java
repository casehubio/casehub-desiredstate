package io.casehub.desiredstate.bridge;

import io.casehub.desiredstate.api.NodeType;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record BridgeDescriptor(
    String protocolId,
    Set<NodeType> handledTypes,
    Map<String, BridgeTypeDescriptor> typeDescriptors,
    BridgeLifecycleConfig lifecycleConfig,
    String credentialRef,
    List<String> trustedKeyIds
) {
    public BridgeDescriptor(String protocolId, Set<NodeType> handledTypes,
                            Map<String, BridgeTypeDescriptor> typeDescriptors,
                            BridgeLifecycleConfig lifecycleConfig) {
        this(protocolId, handledTypes, typeDescriptors, lifecycleConfig, null, List.of());
    }

    public BridgeDescriptor(String protocolId, Set<NodeType> handledTypes,
                            Map<String, BridgeTypeDescriptor> typeDescriptors,
                            BridgeLifecycleConfig lifecycleConfig,
                            String credentialRef) {
        this(protocolId, handledTypes, typeDescriptors, lifecycleConfig, credentialRef, List.of());
    }
}
