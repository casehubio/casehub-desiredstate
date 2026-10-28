package io.casehub.desiredstate.bridge.terraform;

import java.util.Map;

public record ProviderSchema(Map<String, ResourceSchema> resourceSchemas, ResourceSchema providerSchema) {
    public ProviderSchema(Map<String, ResourceSchema> resourceSchemas) {
        this(resourceSchemas, new ResourceSchema(Map.of()));
    }
}
