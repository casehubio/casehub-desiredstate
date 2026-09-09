package io.casehub.desiredstate.plugin.api;

import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlNodeSpecTest {

    @Test
    void implementsNodeSpec() {
        var spec = new YamlNodeSpec(
            NodeType.of("k8s-deployment"),
            HumanGating.NONE,
            Map.of("namespace", "default", "replicas", 3));

        assertThat(spec).isInstanceOf(NodeSpec.class);
        assertThat(spec.nodeType()).isEqualTo(NodeType.of("k8s-deployment"));
        assertThat(spec.humanGating()).isEqualTo(HumanGating.NONE);
    }

    @Test
    void fieldsAccessible() {
        var spec = new YamlNodeSpec(
            NodeType.of("test"),
            HumanGating.NONE,
            Map.of("namespace", "default", "replicas", 3));

        assertThat(spec.get("namespace")).isEqualTo("default");
        assertThat(spec.get("replicas")).isEqualTo(3);
        assertThat(spec.get("nonexistent")).isNull();
        assertThat(spec.fields()).hasSize(2);
    }

    @Test
    void fieldsAreImmutable() {
        var mutable = new java.util.HashMap<String, Object>();
        mutable.put("name", "test");
        var spec = new YamlNodeSpec(NodeType.of("t"), HumanGating.NONE, mutable);

        mutable.put("injected", "bad");
        assertThat(spec.get("injected")).isNull();
    }

    @Test
    void humanGatingPropagated() {
        var spec = new YamlNodeSpec(
            NodeType.of("test"),
            HumanGating.ALL,
            Map.of());

        assertThat(spec.humanGating()).isEqualTo(HumanGating.ALL);
    }
}
