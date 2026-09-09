package io.casehub.desiredstate.plugin.api.expr;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpressionEvaluatorTest {

    private boolean eval(String expr, Map<String, Object> bindings) {
        return ExpressionEvaluator.evaluate(expr, bindings);
    }

    @Test
    void equality() {
        assertThat(eval("200 == 200", Map.of())).isTrue();
        assertThat(eval("200 == 404", Map.of())).isFalse();
        assertThat(eval("\"hello\" == \"hello\"", Map.of())).isTrue();
        assertThat(eval("\"hello\" == \"world\"", Map.of())).isFalse();
    }

    @Test
    void inequality() {
        assertThat(eval("200 != 404", Map.of())).isTrue();
        assertThat(eval("200 != 200", Map.of())).isFalse();
    }

    @Test
    void numericComparison() {
        assertThat(eval("3 > 0", Map.of())).isTrue();
        assertThat(eval("0 > 3", Map.of())).isFalse();
        assertThat(eval("3 >= 3", Map.of())).isTrue();
        assertThat(eval("2 >= 3", Map.of())).isFalse();
        assertThat(eval("0 < 3", Map.of())).isTrue();
        assertThat(eval("3 <= 3", Map.of())).isTrue();
    }

    @Test
    void setMembership() {
        assertThat(eval("200 in [200, 201]", Map.of())).isTrue();
        assertThat(eval("404 in [200, 201]", Map.of())).isFalse();
        assertThat(eval("\"a\" in [\"a\", \"b\", \"c\"]", Map.of())).isTrue();
    }

    @Test
    void containsSubstring() {
        assertThat(eval("\"Ready or not\" contains \"Ready\"", Map.of())).isTrue();
        assertThat(eval("\"Ready or not\" contains \"Missing\"", Map.of())).isFalse();
    }

    @Test
    void booleanAnd() {
        assertThat(eval("200 == 200 and 3 > 0", Map.of())).isTrue();
        assertThat(eval("200 == 200 and 3 < 0", Map.of())).isFalse();
    }

    @Test
    void booleanOr() {
        assertThat(eval("200 == 404 or 3 > 0", Map.of())).isTrue();
        assertThat(eval("200 == 404 or 3 < 0", Map.of())).isFalse();
    }

    @Test
    void negation() {
        assertThat(eval("not 200 == 404", Map.of())).isTrue();
        assertThat(eval("not 200 == 200", Map.of())).isFalse();
    }

    @Test
    void nullEqualsNull() {
        assertThat(eval("null == null", Map.of())).isTrue();
    }

    @Test
    void nullComparisonIsFalse() {
        assertThat(eval("null == 200", Map.of())).isFalse();
        assertThat(eval("null > 0", Map.of())).isFalse();
        assertThat(eval("null < 0", Map.of())).isFalse();
        assertThat(eval("null contains \"x\"", Map.of())).isFalse();
    }

    @Test
    void interpolatedValues() {
        assertThat(eval("${status} == 200", Map.of("status", 200))).isTrue();
        assertThat(eval("${status} == 200", Map.of("status", 404))).isFalse();
        assertThat(eval("${count} < ${max}", Map.of("count", 2, "max", 5))).isTrue();
    }

    @Test
    void interpolatedNullValue() {
        assertThat(eval("${missing} == 200", Map.of())).isFalse();
    }

    @Test
    void interpolatedStringValue() {
        assertThat(eval("${name} == \"app\"", Map.of("name", "app"))).isTrue();
    }

    @Test
    void mixedNumericAndStringEquality() {
        assertThat(eval("200 == \"200\"", Map.of())).isTrue();
    }

    @Test
    void complexExpression() {
        var bindings = Map.<String, Object>of("status", 200, "replicas", 3, "desired", 3);
        assertThat(eval("${status} == 200 and ${replicas} >= ${desired}", bindings)).isTrue();
    }

    @Test
    void invalidSyntaxThrows() {
        assertThatThrownBy(() -> eval("200 ===", Map.of()))
            .isInstanceOf(ExpressionParseException.class);
    }

    @Test
    void emptyExpressionThrows() {
        assertThatThrownBy(() -> eval("", Map.of()))
            .isInstanceOf(ExpressionParseException.class);
    }

    @Test
    void operatorPrecedence() {
        assertThat(eval("1 == 1 or 2 == 3 and 4 == 5", Map.of())).isTrue();
        assertThat(eval("1 == 2 or 2 == 2 and 3 == 3", Map.of())).isTrue();
    }
}
