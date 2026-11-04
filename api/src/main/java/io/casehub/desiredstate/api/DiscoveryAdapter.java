package io.casehub.desiredstate.api;

import java.util.List;
import java.util.Set;

public interface DiscoveryAdapter {
    Set<NodeType> handledTypes();
    List<DiscoveredNode> discover(Set<NodeType> types, String tenancyId);
}
