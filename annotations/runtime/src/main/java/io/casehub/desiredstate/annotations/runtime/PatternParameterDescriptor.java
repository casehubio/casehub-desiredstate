package io.casehub.desiredstate.annotations.runtime;

public record PatternParameterDescriptor(
        PatternKind kind,
        String nodeType,
        String of,
        Direction direction) {}
