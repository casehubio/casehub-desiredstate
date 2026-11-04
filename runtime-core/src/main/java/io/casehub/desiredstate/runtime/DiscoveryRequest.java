package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.NodeType;

import java.util.Set;

public record DiscoveryRequest(
    Set<NodeType> types,
    String tenancyId,
    String namespace,
    String name
) {
    public DiscoveryRequest {
        if (types == null) types = Set.of();
    }
}
