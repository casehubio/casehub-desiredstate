package io.casehub.desiredstate.api;

@FunctionalInterface
public interface CompletionCondition {
    boolean isComplete(DesiredStateGraph desired, ActualState actual);

    static CompletionCondition allPresent() {
        return (desired, actual) -> desired.nodes().keySet().stream()
            .allMatch(id -> actual.statuses().getOrDefault(id, NodeStatus.UNKNOWN) == NodeStatus.PRESENT);
    }

    static CompletionCondition allSatisfied() {
        return (desired, actual) -> desired.nodes().entrySet().stream().allMatch(e -> {
            NodeStatus status = actual.statuses().getOrDefault(e.getKey(), NodeStatus.UNKNOWN);
            return switch (e.getValue().targetStatus()) {
                case ACTIVE -> status == NodeStatus.PRESENT;
                case SUSPENDED -> status == NodeStatus.SUSPENDED;
            };
        });
    }


    static CompletionCondition never() { return new Never(); }

    record Never() implements CompletionCondition {
        @Override
        public boolean isComplete(DesiredStateGraph desired, ActualState actual) { return false; }
    }
}
