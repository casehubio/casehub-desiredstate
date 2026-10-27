package io.casehub.desiredstate.bridge.terraform;

import java.util.Map;

public record ProviderSchema(Map<String, ResourceSchema> resourceSchemas) {}
