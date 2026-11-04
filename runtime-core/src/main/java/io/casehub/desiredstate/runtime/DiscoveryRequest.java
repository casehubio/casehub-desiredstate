package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.NodeType;

import java.util.Objects;
import java.util.Set;

public record DiscoveryRequest(
    Set<NodeType> types,
    String tenancyId,
    String namespace,
    String name
) {
    public DiscoveryRequest {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
        if (types == null) types = Set.of();
    }
}
