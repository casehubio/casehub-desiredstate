package io.casehub.desiredstate.bridge.terraform;

import java.util.Map;

public record ResourceSchema(Map<String, AttributeSchema> attributes) {}
