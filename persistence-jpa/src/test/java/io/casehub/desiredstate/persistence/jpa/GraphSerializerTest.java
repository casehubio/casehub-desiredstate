package io.casehub.desiredstate.persistence.jpa;

import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.HookDescriptor;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.LifecycleStep;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.api.OrderingConstraint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphSerializerTest {

    private final GraphSerializer serializer = new GraphSerializer();

    @NodeTypeId("test-alpha")
    public record AlphaSpec(String label, int priority) implements NodeSpec {
        @Override
        public NodeType nodeType() {
            return NodeType.of("test-alpha");
        }
    }

    @NodeTypeId("test-beta")
    public record BetaSpec(String region) implements NodeSpec {
        @Override
        public NodeType nodeType() {
            return NodeType.of("test-beta");
        }
    }

    @Test
    void roundTrip_multipleNodeTypes_withDependencies() {
        DesiredNode alpha = new DesiredNode(NodeId.of("a1"), new AlphaSpec("first", 1), HumanGating.NONE);
        DesiredNode beta = new DesiredNode(NodeId.of("b1"), new BetaSpec("eu-west"), HumanGating.PROVISION_ONLY);
        Dependency dep = new Dependency(NodeId.of("b1"), NodeId.of("a1"));

        DesiredStateGraphFactory factory = new TestGraphFactory();
        DesiredStateGraph original = factory.of(List.of(alpha, beta), List.of(dep));

        String json = serializer.serialize(original);
        DesiredStateGraph restored = serializer.deserialize(json, factory);

        assertThat(restored.nodes()).hasSize(2);
        assertThat(restored.dependencies()).containsExactly(dep);

        DesiredNode restoredAlpha = restored.nodes().get(NodeId.of("a1"));
        assertThat(restoredAlpha.spec()).isInstanceOf(AlphaSpec.class);
        AlphaSpec alphaSpec = (AlphaSpec) restoredAlpha.spec();
        assertThat(alphaSpec.label()).isEqualTo("first");
        assertThat(alphaSpec.priority()).isEqualTo(1);
        assertThat(restoredAlpha.humanGating()).isEqualTo(HumanGating.NONE);

        DesiredNode restoredBeta = restored.nodes().get(NodeId.of("b1"));
        assertThat(restoredBeta.spec()).isInstanceOf(BetaSpec.class);
        assertThat(((BetaSpec) restoredBeta.spec()).region()).isEqualTo("eu-west");
        assertThat(restoredBeta.humanGating()).isEqualTo(HumanGating.PROVISION_ONLY);
    }

    @Test
    void roundTrip_emptyGraph() {
        DesiredStateGraphFactory factory = new TestGraphFactory();
        DesiredStateGraph original = factory.of(List.of(), List.of());

        String json = serializer.serialize(original);
        DesiredStateGraph restored = serializer.deserialize(json, factory);

        assertThat(restored.nodes()).isEmpty();
        assertThat(restored.dependencies()).isEmpty();
    }

    @Test
    void roundTrip_withHooks() {
        HookDescriptor hooks = new HookDescriptor(
                List.of(new LifecycleStep.Verify("http://health", 30)),
                List.of(new LifecycleStep.Notify("ops", "deployed")),
                List.of(),
                List.of(new LifecycleStep.Wait(10))
        );
        DesiredNode node = new DesiredNode(NodeId.of("n1"), new AlphaSpec("hooked", 5), HumanGating.ALL, hooks);

        DesiredStateGraphFactory factory = new TestGraphFactory();
        DesiredStateGraph original = factory.of(List.of(node), List.of());

        String json = serializer.serialize(original);
        DesiredStateGraph restored = serializer.deserialize(json, factory);

        DesiredNode restoredNode = restored.nodes().get(NodeId.of("n1"));
        assertThat(restoredNode.hooks()).isNotNull();
        assertThat(restoredNode.hooks().provisionPre()).hasSize(1);
        assertThat(restoredNode.hooks().provisionPre().getFirst()).isInstanceOf(LifecycleStep.Verify.class);
        LifecycleStep.Verify verify = (LifecycleStep.Verify) restoredNode.hooks().provisionPre().getFirst();
        assertThat(verify.url()).isEqualTo("http://health");
        assertThat(verify.timeoutSeconds()).isEqualTo(30);
        assertThat(restoredNode.hooks().provisionPost()).hasSize(1);
        assertThat(restoredNode.hooks().deprovisionPost()).hasSize(1);
        assertThat(restoredNode.hooks().deprovisionPre()).isEmpty();
    }

    @Test
    void deserialize_returnsNull_onMalformedJson() {
        DesiredStateGraphFactory factory = new TestGraphFactory();
        DesiredStateGraph result = serializer.deserialize("not valid json {{{", factory);
        assertThat(result).isNull();
    }

    @Test
    void deserialize_returnsNull_onUnknownSpecClass() {
        String json = """
                {"nodes":[{"id":"x1","specClass":"com.nonexistent.Spec","spec":{},"humanGating":"NONE","hooks":null}],"dependencies":[]}
                """;
        DesiredStateGraphFactory factory = new TestGraphFactory();
        DesiredStateGraph result = serializer.deserialize(json, factory);
        assertThat(result).isNull();
    }

    @Test
    void roundTrip_withOrderingConstraints() {
        DesiredNode alpha = new DesiredNode(NodeId.of("a1"), new AlphaSpec("first", 1), HumanGating.NONE);
        DesiredNode beta  = new DesiredNode(NodeId.of("b1"), new BetaSpec("eu-west"), HumanGating.NONE);
        OrderingConstraint constraint = new OrderingConstraint(
                NodeType.of("test-alpha"), NodeType.of("test-beta"));

        DesiredStateGraphFactory factory = new TestGraphFactory();
        DesiredStateGraph original = factory.of(
                List.of(alpha, beta), List.of(),
                java.util.Set.of(constraint));

        String            json     = serializer.serialize(original);
        DesiredStateGraph restored = serializer.deserialize(json, factory);

        assertThat(restored.nodes()).hasSize(2);
        assertThat(restored.orderingConstraints()).containsExactly(constraint);
    }

    @Test
    void roundTrip_withoutOrderingConstraints_deserializesCleanly() {
        DesiredNode alpha = new DesiredNode(NodeId.of("a1"), new AlphaSpec("first", 1), HumanGating.NONE);

        DesiredStateGraphFactory factory  = new TestGraphFactory();
        DesiredStateGraph        original = factory.of(List.of(alpha), List.of());

        String            json     = serializer.serialize(original);
        DesiredStateGraph restored = serializer.deserialize(json, factory);

        assertThat(restored.orderingConstraints()).isEmpty();
    }


    private static class TestGraphFactory extends TestDesiredStateGraphFactory {
    }
}
