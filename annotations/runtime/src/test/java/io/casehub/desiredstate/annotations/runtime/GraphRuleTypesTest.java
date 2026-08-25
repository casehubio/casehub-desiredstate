package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.annotations.DirectDep;
import io.casehub.desiredstate.annotations.GraphRule;
import io.casehub.desiredstate.annotations.Match;
import io.casehub.desiredstate.annotations.NotExists;
import io.casehub.desiredstate.annotations.Reaches;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphRuleTypesTest {

    @Test
    void graphRuleAnnotationHasCorrectTargets() {
        var targets = GraphRule.class.getAnnotation(java.lang.annotation.Target.class).value();
        assertThat(targets).containsExactlyInAnyOrder(ElementType.TYPE, ElementType.METHOD);
    }

    @Test
    void graphRuleAnnotationHasRuntimeRetention() {
        var retention = GraphRule.class.getAnnotation(java.lang.annotation.Retention.class).value();
        assertThat(retention).isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void graphRuleDefaultGraphIsEmpty() throws Exception {
        var graphMethod = GraphRule.class.getMethod("graph");
        assertThat(graphMethod.getDefaultValue()).isEqualTo("");
    }

    @Test
    void matchAnnotationTargetsParameter() {
        var targets = Match.class.getAnnotation(java.lang.annotation.Target.class).value();
        assertThat(targets).containsExactly(ElementType.PARAMETER);
    }

    @Test
    void directDepHasDefaultDirection() throws Exception {
        var dirMethod = DirectDep.class.getMethod("direction");
        assertThat(dirMethod.getDefaultValue()).isEqualTo(Direction.DEPENDENCIES);
    }

    @Test
    void directDepHasDefaultOfEmpty() throws Exception {
        var ofMethod = DirectDep.class.getMethod("of");
        assertThat(ofMethod.getDefaultValue()).isEqualTo("");
    }

    @Test
    void reachesHasDefaultDirection() throws Exception {
        var dirMethod = Reaches.class.getMethod("direction");
        assertThat(dirMethod.getDefaultValue()).isEqualTo(Direction.DEPENDENCIES);
    }

    @Test
    void notExistsHasDefaultDirection() throws Exception {
        var dirMethod = NotExists.class.getMethod("direction");
        assertThat(dirMethod.getDefaultValue()).isEqualTo(Direction.DEPENDENCIES);
    }

    @Test
    void directionEnumHasTwoValues() {
        assertThat(Direction.values()).containsExactly(Direction.DEPENDENCIES, Direction.DEPENDENTS);
    }

    @Test
    void patternKindEnumValues() {
        assertThat(PatternKind.values()).containsExactly(
                PatternKind.MATCH, PatternKind.DIRECT_DEP,
                PatternKind.REACHES, PatternKind.NOT_EXISTS);
    }

    @Test
    void patternParameterDescriptorConstruction() {
        var desc = new PatternParameterDescriptor(
                PatternKind.MATCH, "transformer", "", Direction.DEPENDENCIES);
        assertThat(desc.kind()).isEqualTo(PatternKind.MATCH);
        assertThat(desc.nodeType()).isEqualTo("transformer");
        assertThat(desc.of()).isEmpty();
        assertThat(desc.direction()).isEqualTo(Direction.DEPENDENCIES);
    }

    @Test
    void graphRuleDescriptorConstruction() {
        var desc = new GraphRuleDescriptor("myRule", true, List.of(), "com.example.MyClass");
        assertThat(desc.methodName()).isEqualTo("myRule");
        assertThat(desc.imperative()).isTrue();
        assertThat(desc.patterns()).isEmpty();
        assertThat(desc.sourceClassName()).isEqualTo("com.example.MyClass");
    }
}
