package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;

import static org.assertj.core.api.Assertions.assertThat;

class NodeTypeIdTest {

    @Test
    void annotationHasRuntimeRetention() {
        assertThat(NodeTypeId.class.getAnnotation(java.lang.annotation.Retention.class).value())
                .isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void annotationTargetsType() {
        assertThat(NodeTypeId.class.getAnnotation(java.lang.annotation.Target.class).value())
                .containsExactly(ElementType.TYPE);
    }

    @NodeTypeId("test-type")
    record TestSpec() {}

    @Test
    void valueIsReadable() {
        assertThat(TestSpec.class.getAnnotation(NodeTypeId.class).value())
                .isEqualTo("test-type");
    }
}
