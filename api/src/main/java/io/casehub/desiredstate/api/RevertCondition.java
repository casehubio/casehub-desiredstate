package io.casehub.desiredstate.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public sealed interface RevertCondition {

    default RevertMode mode() {
        return switch (this) {
            case OnDuration d -> RevertMode.DURATION;
            case OnSchedule s -> RevertMode.SCHEDULE;
            case OnStatusChange e -> RevertMode.STATUS_CHANGE;
            case Never n -> RevertMode.NEVER;
        };
    }

    record OnDuration(Duration duration) implements RevertCondition {
        public OnDuration { Objects.requireNonNull(duration, "duration"); }
    }

    record OnSchedule(String cronExpression) implements RevertCondition {
        public OnSchedule { Objects.requireNonNull(cronExpression, "cronExpression"); }
    }

    record OnStatusChange(Set<NodeStatus> triggerStatuses) implements RevertCondition {
        public OnStatusChange {
            Objects.requireNonNull(triggerStatuses, "triggerStatuses");
            triggerStatuses = Set.copyOf(triggerStatuses);
        }
    }

    record Never() implements RevertCondition {}
}
