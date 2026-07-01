package io.casehub.desiredstate.api;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class CoreTypesTest {
    record TestSpec(String name, int size) implements NodeSpec {}
    record HumanSpec(String name) implements NodeSpec {
        @Override public boolean requiresHuman() { return true; }
    }

    @Test void nodeId_equality() {
        var a = new NodeId("library");
        var b = new NodeId("library");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.value()).isEqualTo("library");
    }

    @Test void nodeType_equality() {
        assertThat(new NodeType("room")).isEqualTo(new NodeType("room"));
    }

    @Test void dependency_semantics() {
        var from = new NodeId("creature");
        var to = new NodeId("room");
        var dep = new Dependency(from, to);
        assertThat(dep.from()).isEqualTo(from);
        assertThat(dep.to()).isEqualTo(to);
    }

    @Test void desiredNode_humanFlag() {
        var spec = new TestSpec("Library", 12);
        var node = new DesiredNode(new NodeId("library"), new NodeType("room"), spec, false);
        assertThat(node.requiresHuman()).isFalse();
        var humanNode = new DesiredNode(new NodeId("dragon"), new NodeType("creature"), spec, true);
        assertThat(humanNode.requiresHuman()).isTrue();
    }

    @Test void nodeSpec_markerInterface() {
        NodeSpec spec = new TestSpec("Crypt", 10);
        assertThat(spec).isInstanceOf(NodeSpec.class);
    }

    @Test void nodeSpec_requiresHuman_defaultFalse() {
        NodeSpec spec = new TestSpec("Library", 12);
        assertThat(spec.requiresHuman()).isFalse();
    }

    @Test void desiredNode_orComposition_specTrue_fieldFalse() {
        var spec = new HumanSpec("dragon-lair");
        var node = new DesiredNode(new NodeId("lair"), new NodeType("room"), spec, false);
        assertThat(node.requiresHuman()).isTrue();
    }

    @Test void desiredNode_orComposition_specFalse_fieldTrue() {
        var spec = new TestSpec("Library", 12);
        var node = new DesiredNode(new NodeId("library"), new NodeType("room"), spec, true);
        assertThat(node.requiresHuman()).isTrue();
    }

    @Test void desiredNode_orComposition_bothFalse() {
        var spec = new TestSpec("Library", 12);
        var node = new DesiredNode(new NodeId("library"), new NodeType("room"), spec, false);
        assertThat(node.requiresHuman()).isFalse();
    }
}
