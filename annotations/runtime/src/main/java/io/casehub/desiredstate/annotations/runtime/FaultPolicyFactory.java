package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.annotations.Customize;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ReviewSpecFactory;
import io.casehub.desiredstate.api.ThresholdFaultPolicy;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Framework-neutral factory for creating ThresholdFaultPolicy from FaultPolicyDescriptor.
 * Extracted from DesiredStateGraphRecorder — no Quarkus dependencies.
 */
public final class FaultPolicyFactory {

    private FaultPolicyFactory() {}

    public static ThresholdFaultPolicy create(FaultPolicyDescriptor descriptor,
                                               String implClassName) {
        try {
            String className = descriptor.sourceClassName() != null
                    ? descriptor.sourceClassName() : implClassName;
            Class<?> implClass = Thread.currentThread().getContextClassLoader()
                    .loadClass(className);
            Object instance = implClass.getDeclaredConstructor().newInstance();

            Set<FaultType> faultTypes = descriptor.faultTypes().stream()
                    .map(FaultType::valueOf)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(FaultType.class)));

            Set<NodeType> nodeTypes = descriptor.nodeTypes().stream()
                    .map(NodeType::of)
                    .collect(Collectors.toSet());

            if (nodeTypes.isEmpty() && descriptor.sourceClassName() != null
                    && instance instanceof NodeSpec nodeSpec) {
                nodeTypes = Set.of(nodeSpec.nodeType());
            }

            Set<NodeType> ignoreTypes = descriptor.ignoreTypes().stream()
                    .map(NodeType::of)
                    .collect(Collectors.toSet());

            ThresholdFaultPolicy.Builder builder = ThresholdFaultPolicy.builder()
                    .faultTypes(faultTypes)
                    .nodeTypes(nodeTypes)
                    .ignoreTypes(ignoreTypes);

            if (!descriptor.namespace().isEmpty()) {
                builder.namespace(descriptor.namespace());
            }

            for (TierDescriptor td : descriptor.tiers()) {
                Method reviewMethod = implClass.getMethod(td.reviewMethodName(),
                        FaultEvent.class, DesiredStateGraph.class);
                ReviewSpecFactory reviewFactory = (event, graph) -> {
                    try {
                        return (NodeSpec) reviewMethod.invoke(instance, event, graph);
                    } catch (Exception e) {
                        throw new RuntimeException("Review method invocation failed: "
                                + reviewMethod.getName(), e);
                    }
                };
                if (!td.nodeType().isEmpty()) {
                    NodeType declaredType = NodeType.of(td.nodeType());
                    ReviewSpecFactory delegate = reviewFactory;
                    reviewFactory = new ReviewSpecFactory() {
                        @Override
                        public NodeSpec create(FaultEvent event, DesiredStateGraph graph) {
                            return delegate.create(event, graph);
                        }
                        @Override
                        public NodeType nodeType() { return declaredType; }
                    };
                }
                builder.tier(td.threshold(), FaultPolicy.addReviewNode(reviewFactory));
            }

            for (Method m : implClass.getMethods()) {
                if (m.isAnnotationPresent(Customize.class)) {
                    var customize = m.getAnnotation(Customize.class);
                    if (!customize.value().isEmpty() && m.getParameterCount() == 1
                            && ThresholdFaultPolicy.Builder.class.isAssignableFrom(m.getParameterTypes()[0])) {
                        m.invoke(null, builder);
                    }
                }
            }

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create fault policy from annotations: "
                    + e.getMessage(), e);
        }
    }
}
