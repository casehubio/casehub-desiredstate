package io.casehub.desiredstate.ts;

import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.annotations.runtime.OrderingConstraintDescriptor;
import io.casehub.desiredstate.api.BeanRegistration;
import io.casehub.desiredstate.api.GoalCompiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TsDslDiscovery {

    public record DiscoveredEnvelope(String name, TsEnvelope single, TsLifecycleEnvelope lifecycle) {}

    public List<BeanRegistration> discover(
            List<DiscoveredEnvelope> envelopes,
            Map<String, String> typeRegistry) {

        List<BeanRegistration> beans = new ArrayList<>();

        for (DiscoveredEnvelope discovered : envelopes) {
            GoalCompiler<?> compiler;
            if (discovered.lifecycle() != null) {
                compiler = TsGoalCompilerFactory.createLifecycle(
                    discovered.lifecycle(), typeRegistry, List.of());
            } else if (discovered.single() != null) {
                GraphDescriptor descriptor = toGraphDescriptor(discovered.single(), typeRegistry);
                compiler = TsGoalCompilerFactory.create(
                    descriptor, typeRegistry, List.of(), List.of(), List.of());
            } else {
                continue;
            }
            beans.add(new BeanRegistration(
                "goalCompiler_ts_" + discovered.name(),
                GoalCompiler.class, compiler));
        }

        return beans;
    }

    private GraphDescriptor toGraphDescriptor(TsEnvelope envelope, Map<String, String> typeRegistry) {
        List<NodeDescriptor> nodes = new ArrayList<>();
        for (TsEnvelopeNode en : envelope.nodes()) {
            String specClassName = typeRegistry.get(en.type());
            nodes.add(new NodeDescriptor.InlineNode(en.id(), specClassName,
                en.spec() != null ? en.spec() : Map.of(), en.humanGating()));
        }
        List<OrderingConstraintDescriptor> constraints = envelope.orderingConstraints().stream()
            .map(c -> new OrderingConstraintDescriptor(c.before(), c.after()))
            .toList();
        return new GraphDescriptor(
            envelope.namespace(), envelope.name(),
            null, null, nodes, envelope.dependencies(),
            List.of(), null, List.of(), List.of(), constraints);
    }
}
