package io.casehub.desiredstate.api;

import java.util.Map;

@FunctionalInterface
public interface NodeSpecFactory {
    NodeSpec create(Map<String, Object> specMap);
}
