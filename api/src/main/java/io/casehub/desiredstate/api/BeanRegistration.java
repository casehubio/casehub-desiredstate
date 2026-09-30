package io.casehub.desiredstate.api;

import java.util.Objects;

public record BeanRegistration(String name, Class<?> type, Object instance) {
    public BeanRegistration {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(instance, "instance");
    }
}
