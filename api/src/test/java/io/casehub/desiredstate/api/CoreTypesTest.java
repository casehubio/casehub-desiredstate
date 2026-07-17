package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreTypesTest {
    record TestSpec(String name, int size) implements NodeSpec {}

    record HumanSpec(String name) implements NodeSpec {
        @Override
        public HumanGating humanGating() {return HumanGating.ALL;}
    }

    @Test
    void nodeId_equality() {
        var a = new NodeId("library");
        var b = new NodeId("library");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.value()).isEqualTo("library");
    }

    @Test
    void nodeType_equality() {
        assertThat(new NodeType("room")).isEqualTo(new NodeType("room"));
    }

    @Test
    void dependency_semantics() {
        var from = new NodeId("creature");
        var to   = new NodeId("room");
        var dep  = new Dependency(from, to);
        assertThat(dep.from()).isEqualTo(from);
        assertThat(dep.to()).isEqualTo(to);
    }

    @Test
    void desiredNode_humanGating() {
        var spec = new TestSpec("Library", 12);
        var node = new DesiredNode(new NodeId("library"), new NodeType("room"), spec, HumanGating.NONE);
        assertThat(node.requiresHuman()).isFalse();
        var humanNode = new DesiredNode(new NodeId("dragon"), new NodeType("creature"), spec, HumanGating.ALL);
        assertThat(humanNode.requiresHuman()).isTrue();
    }

    @Test
    void nodeSpec_markerInterface() {
        NodeSpec spec = new TestSpec("Crypt", 10);
        assertThat(spec).isInstanceOf(NodeSpec.class);
    }

    @Test
    void nodeSpec_humanGating_defaultNone() {
        NodeSpec spec = new TestSpec("Library", 12);
        assertThat(spec.humanGating()).isEqualTo(HumanGating.NONE);
    }

    @Test
    void desiredNode_orComposition_specAll_nodeNone() {
        var spec = new HumanSpec("dragon-lair");
        var node = new DesiredNode(new NodeId("lair"), new NodeType("room"), spec, HumanGating.NONE);
        assertThat(node.requiresHuman()).isTrue();
        assertThat(node.requiresHuman(StepAction.PROVISION)).isTrue();
        assertThat(node.requiresHuman(StepAction.DEPROVISION)).isTrue();
    }

    @Test
    void desiredNode_orComposition_specNone_nodeAll() {
        var spec = new TestSpec("Library", 12);
        var node = new DesiredNode(new NodeId("library"), new NodeType("room"), spec, HumanGating.ALL);
        assertThat(node.requiresHuman()).isTrue();
    }

    @Test
    void desiredNode_orComposition_bothNone() {
        var spec = new TestSpec("Library", 12);
        var node = new DesiredNode(new NodeId("library"), new NodeType("room"), spec, HumanGating.NONE);
        assertThat(node.requiresHuman()).isFalse();
    }

    @Test
    void requiresHuman_perAction_nodeProvisionOnly_specNone() {
        NodeSpec    spec = new TestSpec("Library", 12);
        DesiredNode node = new DesiredNode(NodeId.of("n"), NodeType.of("t"), spec, HumanGating.PROVISION_ONLY);
        assertThat(node.requiresHuman(StepAction.PROVISION)).isTrue();
        assertThat(node.requiresHuman(StepAction.DEPROVISION)).isFalse();
        assertThat(node.requiresHuman()).isTrue();
    }

    @Test
    void requiresHuman_perAction_nodeNone_specDeprovisionOnly() {
        NodeSpec spec = new NodeSpec() {
            @Override
            public HumanGating humanGating() {return HumanGating.DEPROVISION_ONLY;}
        };
        DesiredNode node = new DesiredNode(NodeId.of("n"), NodeType.of("t"), spec, HumanGating.NONE);
        assertThat(node.requiresHuman(StepAction.PROVISION)).isFalse();
        assertThat(node.requiresHuman(StepAction.DEPROVISION)).isTrue();
    }

    @Test
    void requiresHuman_perAction_merge_nodeProvisionOnly_specDeprovisionOnly() {
        NodeSpec spec = new NodeSpec() {
            @Override
            public HumanGating humanGating() {return HumanGating.DEPROVISION_ONLY;}
        };
        DesiredNode node = new DesiredNode(NodeId.of("n"), NodeType.of("t"), spec, HumanGating.PROVISION_ONLY);
        assertThat(node.requiresHuman(StepAction.PROVISION)).isTrue();
        assertThat(node.requiresHuman(StepAction.DEPROVISION)).isTrue();
        assertThat(node.requiresHuman()).isTrue();
    }

    @Test
    void humanGating_nullRejected() {
        assertThatThrownBy(() -> new DesiredNode(NodeId.of("n"), NodeType.of("t"), new TestSpec("x", 1), null))
                .isInstanceOf(NullPointerException.class);
    }
}
