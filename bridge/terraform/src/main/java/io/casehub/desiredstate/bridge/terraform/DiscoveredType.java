package io.casehub.desiredstate.bridge.terraform;

import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.bridge.BridgeTypeDescriptor;
import io.casehub.desiredstate.plugin.api.PluginSpecSchema;

public record DiscoveredType(
    NodeType nodeType,
    PluginSpecSchema specSchema,
    BridgeTypeDescriptor typeDescriptor
) {}
