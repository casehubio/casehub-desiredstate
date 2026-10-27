package io.casehub.desiredstate.bridge;

public record ResourceChange(String typeId, String resourceId, ChangeAction action) {}
