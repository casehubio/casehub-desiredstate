package io.casehub.desiredstate.api;

import java.util.Map;

public interface NodeSpecFactoryProvider {
    Map<String, NodeSpecFactory> provide();
}
