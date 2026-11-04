package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DiscoveredNode;

import java.util.List;

public record DiscoveryResult(
    List<DiscoveredNode> resources,
    DesiredStateGraph graph
) {}
