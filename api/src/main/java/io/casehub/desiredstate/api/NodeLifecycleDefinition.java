package io.casehub.desiredstate.api;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record NodeLifecycleDefinition(
    NodeType nodeType,
    Set<Transition> transitions,
    Map<NodeLifecycleState, List<TransitionAction>> onEnter,
    Map<NodeLifecycleState, List<TransitionAction>> onExit
) {
    public record Transition(NodeLifecycleState from, NodeLifecycleState to) {
        public Transition {
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
        }
    }

    public NodeLifecycleDefinition {
        Objects.requireNonNull(nodeType, "nodeType must not be null");
        transitions = Set.copyOf(transitions);
        onEnter = Map.copyOf(onEnter);
        onExit = Map.copyOf(onExit);
    }

    public boolean supportsSuspendResume() {
        return transitions.stream().anyMatch(t ->
            t.from() == NodeLifecycleState.SUSPENDING || t.to() == NodeLifecycleState.SUSPENDING
            || t.from() == NodeLifecycleState.RESUMING || t.to() == NodeLifecycleState.RESUMING);
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        Set<NodeLifecycleState> transientStates = EnumSet.of(
            NodeLifecycleState.PROVISIONING, NodeLifecycleState.DEPROVISIONING,
            NodeLifecycleState.SUSPENDING, NodeLifecycleState.RESUMING);
        Set<NodeLifecycleState> persistentStates = EnumSet.of(
            NodeLifecycleState.ABSENT, NodeLifecycleState.PRESENT,
            NodeLifecycleState.DRIFTED, NodeLifecycleState.SUSPENDED);

        for (NodeLifecycleState transientState : transientStates) {
            boolean referenced = transitions.stream()
                .anyMatch(t -> t.from() == transientState || t.to() == transientState);
            if (referenced) {
                boolean hasExit = transitions.stream().anyMatch(t ->
                    t.from() == transientState && persistentStates.contains(t.to()));
                if (!hasExit) {
                    errors.add("Transient state " + transientState
                               + " has no exit transition to a persistent state");
                }
            }
        }

        boolean absentAsFrom = transitions.stream()
            .anyMatch(t -> t.from() == NodeLifecycleState.ABSENT);
        if (!absentAsFrom) {
            errors.add("ABSENT must appear as a 'from' state in at least one transition");
        }

        Set<NodeLifecycleState> allStates = EnumSet.noneOf(NodeLifecycleState.class);
        transitions.forEach(t -> { allStates.add(t.from()); allStates.add(t.to()); });
        for (NodeLifecycleState s : onEnter.keySet()) {
            if (!allStates.contains(s)) {
                errors.add("onEnter references orphan state " + s);
            }
        }
        for (NodeLifecycleState s : onExit.keySet()) {
            if (!allStates.contains(s)) {
                errors.add("onExit references orphan state " + s);
            }
        }

        return errors;
    }
}
