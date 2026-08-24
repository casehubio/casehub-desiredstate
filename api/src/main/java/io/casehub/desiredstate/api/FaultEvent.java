package io.casehub.desiredstate.api;

import java.util.Objects;

public record FaultEvent(NodeId node, FaultType type, String detail) {
    public FaultEvent {
        Objects.requireNonNull(node, "FaultEvent.node must not be null");
        Objects.requireNonNull(type, "FaultEvent.type must not be null");
        Objects.requireNonNull(detail, "FaultEvent.detail must not be null");
    }

    public static FaultEvent probe() {
        return new FaultEvent(NodeId.of("__probe__"), FaultType.PROVISION_FAILED, "probe");
    }
}
