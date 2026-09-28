package io.casehub.desiredstate.api;

/**
 * The observed status of a node in the actual environment.
 */
public enum NodeStatus {
    /** Node exists and matches its spec. */
    PRESENT,
    /** Node does not exist. */
    ABSENT,
    /** Node exists but has diverged from its spec. */
    DRIFTED,
    /** Node status could not be determined. */
    UNKNOWN,
    /** Node is dormant — process stopped but state persists on disk/storage. */
    SUSPENDED
}
