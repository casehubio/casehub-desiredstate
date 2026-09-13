package io.casehub.desiredstate.api;

public record DomainId(String value) {
    public DomainId {
        java.util.Objects.requireNonNull(value, "DomainId value must not be null");
        if (value.isBlank()) throw new IllegalArgumentException("DomainId must not be blank");
    }
    public static DomainId of(String value) { return new DomainId(value); }
}
