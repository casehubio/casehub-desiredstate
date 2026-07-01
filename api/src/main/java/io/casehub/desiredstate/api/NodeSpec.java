package io.casehub.desiredstate.api;

/**
 * Marker interface for domain-specific node specifications.
 * Each domain (infra, compliance, IoT, deployment) provides its own implementations.
 */
public interface NodeSpec {
    default boolean requiresHuman() { return false; }
}
