package io.casehub.desiredstate.api;

import java.util.List;
import java.util.Objects;

public record TransitionPlan(
        List<List<OrderedStep>> removals,
        List<List<OrderedStep>> suspensions,
        List<List<OrderedStep>> resumptions,
        List<List<OrderedStep>> additions,
        DesiredStateGraph before, DesiredStateGraph after
) {
    public static TransitionPlan ofFlat(List<OrderedStep> removals, List<OrderedStep> additions,
                                        DesiredStateGraph before, DesiredStateGraph after) {
        return new TransitionPlan(List.of(removals), List.of(), List.of(), List.of(additions), before, after);
    }

    public static TransitionPlan ofFlat(List<OrderedStep> removals, List<OrderedStep> suspensions,
                                        List<OrderedStep> resumptions, List<OrderedStep> additions,
                                        DesiredStateGraph before, DesiredStateGraph after) {
        return new TransitionPlan(List.of(removals), List.of(suspensions), List.of(resumptions),
                                  List.of(additions), before, after);
    }

    public TransitionPlan {
        removals    = removals.stream().map(List::copyOf).toList();
        suspensions = suspensions.stream().map(List::copyOf).toList();
        resumptions = resumptions.stream().map(List::copyOf).toList();
        additions   = additions.stream().map(List::copyOf).toList();
        Objects.requireNonNull(before, "TransitionPlan.before must not be null");
        Objects.requireNonNull(after, "TransitionPlan.after must not be null");
    }

    public List<OrderedStep> flatRemovals()    {return removals.stream().flatMap(List::stream).toList();}

    public List<OrderedStep> flatSuspensions() {return suspensions.stream().flatMap(List::stream).toList();}

    public List<OrderedStep> flatResumptions() {return resumptions.stream().flatMap(List::stream).toList();}

    public List<OrderedStep> flatAdditions()   {return additions.stream().flatMap(List::stream).toList();}

    public boolean isEmpty() {
        return removals.stream().allMatch(List::isEmpty)
               && suspensions.stream().allMatch(List::isEmpty)
               && resumptions.stream().allMatch(List::isEmpty)
               && additions.stream().allMatch(List::isEmpty);
    }
}
