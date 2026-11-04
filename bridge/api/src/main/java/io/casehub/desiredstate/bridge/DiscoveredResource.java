package io.casehub.desiredstate.bridge;

import java.util.Map;

public record DiscoveredResource(
    String resourceId,
    String typeId,
    Map<String, Object> state
) {
    public DiscoveredResource {
        if (state == null) state = Map.of();
    }
}
