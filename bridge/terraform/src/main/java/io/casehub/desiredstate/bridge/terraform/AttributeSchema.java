package io.casehub.desiredstate.bridge.terraform;

public record AttributeSchema(
        String name,
        String type,
        boolean required,
        boolean optional,
        boolean computed,
        boolean sensitive,
        boolean deprecated,
        String description
) {}
