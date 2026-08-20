package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class NodeSpecValueSemanticsTest {

    private record SpecA(String x, int y) implements NodeSpec { @Override public NodeType nodeType() { return NodeType.of("test"); } }
    private record SpecB(double value) implements NodeSpec { @Override public NodeType nodeType() { return NodeType.of("test"); } }

    @Test
    void recordNodeSpecs_shouldHaveValueEquality() {
        assertThat(new SpecA("hello", 42)).isEqualTo(new SpecA("hello", 42));
        assertThat(new SpecA("hello", 42).hashCode()).isEqualTo(new SpecA("hello", 42).hashCode());
    }

    @Test
    void differentFieldValues_shouldNotBeEqual() {
        assertThat(new SpecA("hello", 42)).isNotEqualTo(new SpecA("hello", 99));
    }

    @Test
    void differentSpecTypes_shouldNotBeEqual() {
        SpecA a = new SpecA("x", 1);
        SpecB b = new SpecB(1.0);
        assertThat(a).isNotEqualTo(b);
    }
}
