package io.casehub.desiredstate.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ThresholdFaultPolicy implements FaultPolicy {

    private final Set<FaultType> faultTypes;
    private final Set<NodeType> nodeTypes;
    private final Set<NodeType> ignoreTypes;
    private final int threshold;
    private final FaultPolicy action;
    private final ConcurrentHashMap<NodeId, Integer> faultCounts = new ConcurrentHashMap<>();

    private ThresholdFaultPolicy(Builder builder) {
        this.faultTypes = Set.copyOf(builder.faultTypes);
        this.nodeTypes = builder.nodeTypes == null ? Set.of() : Set.copyOf(builder.nodeTypes);
        this.ignoreTypes = builder.ignoreTypes == null ? Set.of() : Set.copyOf(builder.ignoreTypes);
        this.threshold = builder.threshold;
        this.action = builder.action;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<GraphMutation> onFault(String tenancyId, FaultEvent event,
                                        DesiredStateGraph current, ActualState actual) {
        DesiredNode node = current.nodes().get(event.node());

        if (node != null && ignoreTypes.contains(node.type())) {
            return List.of();
        }

        if (!faultTypes.contains(event.type())) {
            return List.of();
        }

        if (node == null) {
            return List.of();
        }

        if (!nodeTypes.isEmpty() && !nodeTypes.contains(node.type())) {
            return List.of();
        }

        int count = faultCounts.merge(event.node(), 1, Integer::sum);
        if (count < threshold) {
            return List.of();
        }

        return action.onFault(tenancyId, event, current, actual);
    }

    public static class Builder {
        private Set<FaultType> faultTypes;
        private Set<NodeType> nodeTypes;
        private Set<NodeType> ignoreTypes;
        private int threshold = 3;
        private FaultPolicy action;

        public Builder faultTypes(Set<FaultType> faultTypes) { this.faultTypes = faultTypes; return this; }
        public Builder nodeTypes(Set<NodeType> nodeTypes) { this.nodeTypes = nodeTypes; return this; }
        public Builder ignoreTypes(Set<NodeType> ignoreTypes) { this.ignoreTypes = ignoreTypes; return this; }
        public Builder threshold(int threshold) { this.threshold = threshold; return this; }
        public Builder action(FaultPolicy action) { this.action = action; return this; }

        public ThresholdFaultPolicy build() {
            Objects.requireNonNull(faultTypes, "faultTypes is required");
            Objects.requireNonNull(action, "action is required");
            if (threshold < 1) {
                throw new IllegalArgumentException("threshold must be >= 1, got " + threshold);
            }
            return new ThresholdFaultPolicy(this);}
    }
}
