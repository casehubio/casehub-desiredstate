package io.casehub.desiredstate.api;

import java.time.Instant;
import java.util.Objects;

public record Exemption(NodeId nodeId, ExemptionSpec spec,
                        Instant grantedAt, Instant expiresAt) {
    public Exemption {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(grantedAt, "grantedAt");
    }

    public boolean shouldRevert(Instant now, NodeStatus currentStatus) {
        if (expiresAt != null && now.isAfter(expiresAt)) {
            return true;
        }
        return switch (spec.revertCondition()) {
            case RevertCondition.OnDuration d ->
                now.isAfter(grantedAt.plus(d.duration()));
            case RevertCondition.OnSchedule s -> false;
            case RevertCondition.OnStatusChange sc ->
                sc.triggerStatuses().contains(currentStatus);
            case RevertCondition.Never n -> false;
        };
    }
}
