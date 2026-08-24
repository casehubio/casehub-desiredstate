package io.casehub.desiredstate.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class ThresholdFaultPolicy implements FaultPolicy {

    public record Tier(int threshold, TypedFaultPolicy action) {
        public Tier {
            if (threshold < 1) throw new IllegalArgumentException("threshold must be >= 1, got " + threshold);
            Objects.requireNonNull(action, "action is required");
        }
    }

    private final Set<FaultType>  faultTypes;
    private final Set<NodeType>   nodeTypes;
    private final Set<NodeType>   ignoreTypes;
    private final List<Tier>      tiers;
    private final FaultCountStore store;
    private final String          namespace;

    private ThresholdFaultPolicy(Builder builder) {
        this.faultTypes  = Set.copyOf(builder.faultTypes);
        this.nodeTypes   = builder.nodeTypes == null ? Set.of() : Set.copyOf(builder.nodeTypes);

        Set<NodeType> merged = new HashSet<>();
        if (builder.ignoreTypes != null) merged.addAll(builder.ignoreTypes);
        for (Tier tier : builder.tiers) {
            merged.add(tier.action().outputNodeType());
        }
        this.ignoreTypes = Set.copyOf(merged);

        this.tiers       = List.copyOf(builder.tiers);
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

        for (int i = tiers.size() - 1; i >= 0; i--) {
            Tier tier = tiers.get(i);
            if (count < tier.threshold()) {
                continue;
            }

            if (i > 0) {
                NodeType previousNodeType = tiers.get(i - 1).action().outputNodeType();
                boolean previousTierPresent = current.dependentsOf(event.node()).stream()
                        .map(depId -> current.nodes().get(depId))
                        .filter(Objects::nonNull)
                        .anyMatch(n -> n.type().equals(previousNodeType));
                if (!previousTierPresent) {
                    continue;
                }
            }

            return tier.action().onFault(tenancyId, event, current, actual);
        }

        return List.of();
    }

    public void resetCount(String tenancyId, NodeId nodeId) {
        store.reset(namespace, tenancyId, nodeId);
    }

    public static class Builder {
        private Set<FaultType>   faultTypes;
        private Set<NodeType>    nodeTypes;
        private Set<NodeType>    ignoreTypes;
        private final List<Tier> tiers = new ArrayList<>();
        private FaultCountStore  store;
        private String           namespace;

        public Builder faultTypes(Set<FaultType> faultTypes) { this.faultTypes = faultTypes; return this; }
        public Builder nodeTypes(Set<NodeType> nodeTypes) { this.nodeTypes = nodeTypes; return this; }
        public Builder ignoreTypes(Set<NodeType> ignoreTypes) { this.ignoreTypes = ignoreTypes; return this; }
        public Builder tier(int threshold, TypedFaultPolicy action) {
            this.tiers.add(new Tier(threshold, action));
            return this;
        }
        public Builder faultCountStore(FaultCountStore store) { this.store = store; return this; }
        public Builder namespace(String namespace) { this.namespace = namespace; return this; }

        public ThresholdFaultPolicy build() {
            Objects.requireNonNull(faultTypes, "faultTypes is required");
            if (tiers.isEmpty()) {
                throw new IllegalArgumentException("at least one tier is required");
            }
            for (int i = 1; i < tiers.size(); i++) {
                if (tiers.get(i).threshold() <= tiers.get(i - 1).threshold()) {
                    throw new IllegalArgumentException(
                        "tier thresholds must be strictly ascending: " +
                        tiers.get(i - 1).threshold() + " >= " + tiers.get(i).threshold());
                }
            }
            if (store != null && namespace == null) {
                throw new IllegalArgumentException("namespace is required when a custom faultCountStore is provided");
            }
            return new ThresholdFaultPolicy(this);
        }
    }
}
