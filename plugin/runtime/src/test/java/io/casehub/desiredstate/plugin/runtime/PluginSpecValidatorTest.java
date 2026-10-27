package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.plugin.api.PluginFieldDef;
import io.casehub.desiredstate.plugin.api.PluginSpecSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginSpecValidatorTest {

    @Test
    void validSpecPassesValidation() {
        var schema = new PluginSpecSchema(Map.of(
            "name", field("string", true),
            "count", field("integer", false)));
        var fields = Map.<String, Object>of("name", "hello", "count", 42);

        assertThatCode(() -> PluginSpecValidator.validate("test-type", fields, schema))
            .doesNotThrowAnyException();
    }

    @Test
    void missingRequiredFieldThrows() {
        var schema = new PluginSpecSchema(Map.of(
            "name", field("string", true)));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type", Map.of(), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("name")
            .hasMessageContaining("required");
    }

    @Test
    void nullRequiredFieldThrows() {
        var schema = new PluginSpecSchema(Map.of(
            "name", field("string", true)));
        var fields = new java.util.HashMap<String, Object>();
        fields.put("name", null);

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type", fields, schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("name")
            .hasMessageContaining("required");
    }

    @Test
    void missingOptionalFieldPasses() {
        var schema = new PluginSpecSchema(Map.of(
            "name", field("string", false)));

        assertThatCode(() -> PluginSpecValidator.validate("test-type", Map.of(), schema))
            .doesNotThrowAnyException();
    }

    @Test
    void stringFieldRejectsNonString() {
        var schema = new PluginSpecSchema(Map.of(
            "name", field("string", true)));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type", Map.of("name", 42), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("name")
            .hasMessageContaining("string");
    }

    @Test
    void integerFieldAcceptsIntAndLong() {
        var schema = new PluginSpecSchema(Map.of(
            "count", field("integer", true)));

        assertThatCode(() -> PluginSpecValidator.validate("test-type", Map.of("count", 42), schema))
            .doesNotThrowAnyException();
        assertThatCode(() -> PluginSpecValidator.validate("test-type", Map.of("count", 42L), schema))
            .doesNotThrowAnyException();
    }

    @Test
    void integerFieldRejectsNonInteger() {
        var schema = new PluginSpecSchema(Map.of(
            "count", field("integer", true)));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type", Map.of("count", "abc"), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("count")
            .hasMessageContaining("integer");
    }

    @Test
    void numberFieldAcceptsAnyNumber() {
        var schema = new PluginSpecSchema(Map.of(
            "ratio", field("number", true)));

        assertThatCode(() -> PluginSpecValidator.validate("test-type", Map.of("ratio", 3.14), schema))
            .doesNotThrowAnyException();
        assertThatCode(() -> PluginSpecValidator.validate("test-type", Map.of("ratio", 42), schema))
            .doesNotThrowAnyException();
    }

    @Test
    void numberFieldRejectsNonNumber() {
        var schema = new PluginSpecSchema(Map.of(
            "ratio", field("number", true)));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type", Map.of("ratio", "abc"), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("ratio")
            .hasMessageContaining("number");
    }

    @Test
    void booleanFieldAcceptsBoolean() {
        var schema = new PluginSpecSchema(Map.of(
            "enabled", field("boolean", true)));

        assertThatCode(() -> PluginSpecValidator.validate("test-type", Map.of("enabled", true), schema))
            .doesNotThrowAnyException();
    }

    @Test
    void booleanFieldRejectsNonBoolean() {
        var schema = new PluginSpecSchema(Map.of(
            "enabled", field("boolean", true)));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type", Map.of("enabled", "yes"), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("enabled")
            .hasMessageContaining("boolean");
    }

    @Test
    void enumFieldAcceptsValidValue() {
        var schema = new PluginSpecSchema(Map.of(
            "region", enumField(true, List.of("us-east", "eu-west"))));

        assertThatCode(() -> PluginSpecValidator.validate("test-type", Map.of("region", "us-east"), schema))
            .doesNotThrowAnyException();
    }

    @Test
    void enumFieldRejectsInvalidValue() {
        var schema = new PluginSpecSchema(Map.of(
            "region", enumField(true, List.of("us-east", "eu-west"))));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type", Map.of("region", "mars"), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("region")
            .hasMessageContaining("mars")
            .hasMessageContaining("us-east");
    }

    @Test
    void listFieldAcceptsList() {
        var schema = new PluginSpecSchema(Map.of(
            "tags", listField(true, "string")));

        assertThatCode(() -> PluginSpecValidator.validate("test-type",
            Map.of("tags", List.of("a", "b")), schema))
            .doesNotThrowAnyException();
    }

    @Test
    void listFieldRejectsNonList() {
        var schema = new PluginSpecSchema(Map.of(
            "tags", listField(true, "string")));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type",
            Map.of("tags", "not-a-list"), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("tags")
            .hasMessageContaining("list");
    }

    @Test
    void mapFieldAcceptsMap() {
        var schema = new PluginSpecSchema(Map.of(
            "labels", mapField(true, "string")));

        assertThatCode(() -> PluginSpecValidator.validate("test-type",
            Map.of("labels", Map.of("env", "prod")), schema))
            .doesNotThrowAnyException();
    }

    @Test
    void mapFieldRejectsNonMap() {
        var schema = new PluginSpecSchema(Map.of(
            "labels", mapField(true, "string")));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type",
            Map.of("labels", "not-a-map"), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("labels")
            .hasMessageContaining("map");
    }

    // --- Pattern constraint ---

    @Test
    void patternMatchPasses() {
        var schema = new PluginSpecSchema(Map.of(
            "email", patternField(true, "^[^@]+@[^@]+$")));

        assertThatCode(() -> PluginSpecValidator.validate("test-type",
            Map.of("email", "user@example.com"), schema))
            .doesNotThrowAnyException();
    }

    @Test
    void patternMismatchThrows() {
        var schema = new PluginSpecSchema(Map.of(
            "email", patternField(true, "^[^@]+@[^@]+$")));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type",
            Map.of("email", "not-an-email"), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("email")
            .hasMessageContaining("pattern");
    }

    // --- Min/Max constraints ---

    @Test
    void numericMinBoundaryPasses() {
        var schema = new PluginSpecSchema(Map.of(
            "count", numericField("integer", true, 1, null)));

        assertThatCode(() -> PluginSpecValidator.validate("test-type", Map.of("count", 1), schema))
            .doesNotThrowAnyException();
    }

    @Test
    void numericBelowMinThrows() {
        var schema = new PluginSpecSchema(Map.of(
            "count", numericField("integer", true, 1, null)));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type", Map.of("count", 0), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("count")
            .hasMessageContaining("min");
    }

    @Test
    void numericMaxBoundaryPasses() {
        var schema = new PluginSpecSchema(Map.of(
            "count", numericField("integer", true, null, 100)));

        assertThatCode(() -> PluginSpecValidator.validate("test-type", Map.of("count", 100), schema))
            .doesNotThrowAnyException();
    }

    @Test
    void numericAboveMaxThrows() {
        var schema = new PluginSpecSchema(Map.of(
            "count", numericField("integer", true, null, 100)));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type", Map.of("count", 101), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("count")
            .hasMessageContaining("max");
    }

    // --- String length constraints ---

    @Test
    void minLengthBoundaryPasses() {
        var schema = new PluginSpecSchema(Map.of(
            "name", stringLengthField(true, 3, null)));

        assertThatCode(() -> PluginSpecValidator.validate("test-type", Map.of("name", "abc"), schema))
            .doesNotThrowAnyException();
    }

    @Test
    void belowMinLengthThrows() {
        var schema = new PluginSpecSchema(Map.of(
            "name", stringLengthField(true, 3, null)));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type", Map.of("name", "ab"), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("name")
            .hasMessageContaining("minLength");
    }

    @Test
    void maxLengthBoundaryPasses() {
        var schema = new PluginSpecSchema(Map.of(
            "name", stringLengthField(true, null, 10)));

        assertThatCode(() -> PluginSpecValidator.validate("test-type", Map.of("name", "0123456789"), schema))
            .doesNotThrowAnyException();
    }

    @Test
    void aboveMaxLengthThrows() {
        var schema = new PluginSpecSchema(Map.of(
            "name", stringLengthField(true, null, 10)));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type", Map.of("name", "01234567890"), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("name")
            .hasMessageContaining("maxLength");
    }

    // --- List items constraints ---

    @Test
    void minItemsBoundaryPasses() {
        var schema = new PluginSpecSchema(Map.of(
            "tags", listFieldWithBounds(true, "string", 2, null)));

        assertThatCode(() -> PluginSpecValidator.validate("test-type",
            Map.of("tags", List.of("a", "b")), schema))
            .doesNotThrowAnyException();
    }

    @Test
    void belowMinItemsThrows() {
        var schema = new PluginSpecSchema(Map.of(
            "tags", listFieldWithBounds(true, "string", 2, null)));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type",
            Map.of("tags", List.of("a")), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("tags")
            .hasMessageContaining("minItems");
    }

    @Test
    void aboveMaxItemsThrows() {
        var schema = new PluginSpecSchema(Map.of(
            "tags", listFieldWithBounds(true, "string", null, 3)));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type",
            Map.of("tags", List.of("a", "b", "c", "d")), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("tags")
            .hasMessageContaining("maxItems");
    }

    // --- Multiple errors ---

    @Test
    void collectsAllErrorsInSingleException() {
        var schema = new PluginSpecSchema(Map.of(
            "name", field("string", true),
            "count", field("integer", true),
            "region", enumField(true, List.of("us-east", "eu-west"))));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type", Map.of(), schema))
            .isInstanceOf(PluginValidationException.class)
            .satisfies(e -> {
                String msg = e.getMessage();
                assertThat(msg).contains("name");
                assertThat(msg).contains("count");
                assertThat(msg).contains("region");
            });
    }

    // --- Item type validation ---

    @Test
    void listItemTypeMismatchThrows() {
        var schema = new PluginSpecSchema(Map.of(
            "ports", listField(true, "integer")));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type",
            Map.of("ports", List.of(80, "not-a-port")), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("ports")
            .hasMessageContaining("integer");
    }

    @Test
    void mapValueTypeMismatchThrows() {
        var schema = new PluginSpecSchema(Map.of(
            "limits", mapField(true, "integer")));

        assertThatThrownBy(() -> PluginSpecValidator.validate("test-type",
            Map.of("limits", Map.of("cpu", "not-a-number")), schema))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("limits")
            .hasMessageContaining("integer");
    }

    // --- Helper methods ---

    private static PluginFieldDef field(String type, boolean required) {
        return new PluginFieldDef(type, required, null, null, null, null,
            null, null, null, null, null, null, null);
    }

    private static PluginFieldDef enumField(boolean required, List<String> values) {
        return new PluginFieldDef("enum", required, null, null, null, null,
            null, null, values, null, null, null, null);
    }

    private static PluginFieldDef listField(boolean required, String itemType) {
        return new PluginFieldDef("list", required, null, null, null, null,
            null, null, null, itemType, null, null, null);
    }

    private static PluginFieldDef listFieldWithBounds(boolean required, String itemType,
                                                       Integer minItems, Integer maxItems) {
        return new PluginFieldDef("list", required, null, null, null, null,
            null, null, null, itemType, null, minItems, maxItems);
    }

    private static PluginFieldDef mapField(boolean required, String valueType) {
        return new PluginFieldDef("map", required, null, null, null, null,
            null, null, null, null, valueType, null, null);
    }

    private static PluginFieldDef patternField(boolean required, String pattern) {
        return new PluginFieldDef("string", required, null, pattern, null, null,
            null, null, null, null, null, null, null);
    }

    private static PluginFieldDef numericField(String type, boolean required,
                                                Number min, Number max) {
        return new PluginFieldDef(type, required, null, null, null, null,
            min, max, null, null, null, null, null);
    }

    private static PluginFieldDef stringLengthField(boolean required,
                                                     Integer minLength, Integer maxLength) {
        return new PluginFieldDef("string", required, null, null, minLength, maxLength,
            null, null, null, null, null, null, null);
    }
}
