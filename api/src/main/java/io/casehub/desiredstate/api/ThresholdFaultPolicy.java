package io.casehub.desiredstate.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class ThresholdFaultPolicy implements FaultPolicy {

    private final Set<FaultType>  faultTypes;
    private final Set<NodeType>   nodeTypes;
    private final Set<NodeType>   ignoreTypes;
    private final int             threshold;
    private final FaultPolicy     action;
    private final FaultCountStore store;
    private final String          namespace;

    private ThresholdFaultPolicy(Builder builder) {
        this.faultTypes  = Set.copyOf(builder.faultTypes);
        this.nodeTypes   = builder.nodeTypes == null ? Set.of() : Set.copyOf(builder.nodeTypes);
        this.ignoreTypes = builder.ignoreTypes == null ? Set.of() : Set.copyOf(builder.ignoreTypes);
        this.threshold   = builder.threshold;
        this.action      = builder.action;
        this.store       = builder.store != null ? builder.store : new InMemoryFaultCountStore();
        this.namespace   = builder.namespace != null ? builder.namespace : deriveNamespace(this.faultTypes);
    }

    private static String deriveNamespace(Set<FaultType> faultTypes) {
        return faultTypes.stream()
                         .map(FaultType::name)
                         .sorted()
                         .collect(Collectors.joining(","));
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

        if (node == null) {
            store.remove(namespace, tenancyId, event.node());
            return List.of();
        }

        if (!faultTypes.contains(event.type())) {
            return List.of();
        }

        if (!nodeTypes.isEmpty() && !nodeTypes.contains(node.type())) {
            return List.of();
        }

        int count = store.incrementAndGet(namespace, tenancyId, event.node());
        if (count < threshold) {
            return List.of();
        }

        return action.onFault(tenancyId, event, current, actual);
    }

    public void resetCount(String tenancyId, NodeId nodeId) {
        store.reset(namespace, tenancyId, nodeId);
    }

    public static class Builder {
        private Set<FaultType>  faultTypes;
        private Set<NodeType>   nodeTypes;
        private Set<NodeType>   ignoreTypes;
        private int             threshold = 3;
        private FaultPolicy     action;
        private FaultCountStore store;
        private String          namespace;

        public Builder faultTypes(Set<FaultType> faultTypes) { this.faultTypes = faultTypes; return this; }
        public Builder nodeTypes(Set<NodeType> nodeTypes) { this.nodeTypes = nodeTypes; return this; }
        public Builder ignoreTypes(Set<NodeType> ignoreTypes) { this.ignoreTypes = ignoreTypes; return this; }
        public Builder threshold(int threshold) { this.threshold = threshold; return this; }
        public Builder action(FaultPolicy action) { this.action = action; return this; }
        public Builder faultCountStore(FaultCountStore store) { this.store = store; return this; }
        public Builder namespace(String namespace) { this.namespace = namespace; return this; }

        public ThresholdFaultPolicy build() {
            Objects.requireNonNull(faultTypes, "faultTypes is required");
            Objects.requireNonNull(action, "action is required");
            if (threshold < 1) {
                throw new IllegalArgumentException("threshold must be >= 1, got " + threshold);
            }
            if (store != null && namespace == null) {
                throw new IllegalArgumentException("namespace is required when a custom faultCountStore is provided");
            }
            return new ThresholdFaultPolicy(this);
        }
    }
}
