package io.casehub.desiredstate.api;

import java.util.List;
import java.util.Objects;

public record TransitionPlan(
        List<OrderedStep> removals,
        List<OrderedStep> suspensions,
        List<OrderedStep> resumptions,
        List<OrderedStep> additions,
        DesiredStateGraph before, DesiredStateGraph after
) {
    public TransitionPlan(List<OrderedStep> removals, List<OrderedStep> additions,
                          DesiredStateGraph before, DesiredStateGraph after) {
        this(removals, List.of(), List.of(), additions, before, after);
    }

    public TransitionPlan {
        removals    = List.copyOf(removals);
        suspensions = List.copyOf(suspensions);
        resumptions = List.copyOf(resumptions);
        additions   = List.copyOf(additions);
        Objects.requireNonNull(before, "TransitionPlan.before must not be null");
        Objects.requireNonNull(after, "TransitionPlan.after must not be null");
    }

    public boolean isEmpty() {
        return removals.isEmpty() && suspensions.isEmpty()
               && resumptions.isEmpty() && additions.isEmpty();
    }
}
