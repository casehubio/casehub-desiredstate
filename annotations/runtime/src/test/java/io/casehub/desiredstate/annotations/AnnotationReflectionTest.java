package io.casehub.desiredstate.annotations;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.api.HumanGating;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;

class AnnotationReflectionTest {

    @Test
    void desiredStateAnnotationPresent() {
        assertThat(DesiredState.class.getAnnotation(Retention.class).value())
                .isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(DesiredState.class.getAnnotation(Target.class).value())
                .containsExactly(ElementType.TYPE);
    }

    @Test
    void desiredStateDefaults() throws Exception {
        assertThat(DesiredState.class.getDeclaredMethod("namespace").getDefaultValue())
                .isEqualTo("");
        assertThat(DesiredState.class.getDeclaredMethod("name").getDefaultValue())
                .isEqualTo("");
    }

    @Test
    void nodeAnnotationHasHumanGatingDefault() throws Exception {
        assertThat(Node.class.getAnnotation(Retention.class).value())
                .isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(Node.class.getAnnotation(Target.class).value())
                .containsExactly(ElementType.METHOD);
        assertThat(Node.class.getDeclaredMethod("humanGating").getDefaultValue())
                .isEqualTo(HumanGating.NONE);
    }

    @Test
    void nodeValueHasNoDefault() {
        assertThat(Node.class.getDeclaredMethods())
                .anySatisfy(m -> {
                    assertThat(m.getName()).isEqualTo("value");
                    assertThat(m.getDefaultValue()).isNull();
                });
    }

    @Test
    void dependsOnTargetsMethod() {
        assertThat(DependsOn.class.getAnnotation(Target.class).value())
                .containsExactly(ElementType.METHOD);
        assertThat(DependsOn.class.getDeclaredMethods())
                .anySatisfy(m -> {
                    assertThat(m.getName()).isEqualTo("value");
                    assertThat(m.getReturnType()).isEqualTo(String[].class);
                });
    }

    @Test
    void faultPolicyDefIsRepeatable() {
        assertThat(FaultPolicyDef.class.isAnnotationPresent(Repeatable.class)).isTrue();
        assertThat(FaultPolicyDef.class.getAnnotation(Repeatable.class).value())
                .isEqualTo(FaultPolicies.class);
    }

    @Test
    void faultPolicyDefTargetsTypeAndMethod() {
        assertThat(FaultPolicyDef.class.getAnnotation(Target.class).value())
                .containsExactlyInAnyOrder(ElementType.TYPE, ElementType.METHOD);
    }

    @Test
    void tierHasNoTarget() {
        assertThat(Tier.class.getAnnotation(Target.class).value()).isEmpty();
    }

    @Test
    void customizeTargetsMethod() throws Exception {
        assertThat(Customize.class.getAnnotation(Target.class).value())
                .containsExactly(ElementType.METHOD);
        assertThat(Customize.class.getDeclaredMethod("value").getDefaultValue())
                .isEqualTo("");
    }

    @Test
    void goalMethodTargetsMethod() {
        assertThat(GoalMethod.class.getAnnotation(Retention.class).value())
                .isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(GoalMethod.class.getAnnotation(Target.class).value())
                .containsExactly(ElementType.METHOD);
    }

    @Test
    void desiredStateQualifierIsQualifier() {
        assertThat(DesiredStateQualifier.class.isAnnotationPresent(
                jakarta.inject.Qualifier.class)).isTrue();
        assertThat(DesiredStateQualifier.class.getAnnotation(Target.class).value())
                .containsExactlyInAnyOrder(
                        ElementType.TYPE, ElementType.METHOD,
                        ElementType.FIELD, ElementType.PARAMETER);
    }
}
