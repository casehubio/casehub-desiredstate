package io.casehub.desiredstate.plugin.testing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluginTestYamlParserTest {

    @Test
    void parsesTestYaml() throws Exception {
        try (var is = getClass().getResourceAsStream(
                "/META-INF/desiredstate/tests/parser-test-fixture.yaml")) {
            var suite = PluginTestYamlParser.parse(is);

            assertThat(suite.pluginType()).isEqualTo("mock-resource");
            assertThat(suite.infrastructureType()).isEqualTo("http-mock");
            assertThat(suite.setup().stubs()).hasSize(1);
            assertThat(suite.setup().variables()).containsKey("auth");
            assertThat(suite.testCases()).hasSize(4);
        }
    }

    @Test
    void parsesProvisionTestCase() throws Exception {
        try (var is = getClass().getResourceAsStream(
                "/META-INF/desiredstate/tests/parser-test-fixture.yaml")) {
            var suite = PluginTestYamlParser.parse(is);
            var provisionTest = suite.testCases().get(0);

            assertThat(provisionTest.name()).isEqualTo("provision succeeds");
            assertThat(provisionTest.action()).isEqualTo("provision");
            assertThat(provisionTest.assertions().get("provision")).isEqualTo("success");
            assertThat(provisionTest.expectations()).hasSize(1);
            assertThat(provisionTest.spec()).containsEntry("name", "test-app");
        }
    }

    @Test
    void parsesActualStateTestCase() throws Exception {
        try (var is = getClass().getResourceAsStream(
                "/META-INF/desiredstate/tests/parser-test-fixture.yaml")) {
            var suite = PluginTestYamlParser.parse(is);
            var actualTest = suite.testCases().get(1);

            assertThat(actualTest.name()).isEqualTo("actual state is present");
            assertThat(actualTest.action()).isEqualTo("actual-state");
            assertThat(actualTest.assertions().get("actual-state")).isEqualTo("PRESENT");
            assertThat(actualTest.expectations()).isEmpty();
        }
    }

    @Test
    void parsesFaultInjection() throws Exception {
        try (var is = getClass().getResourceAsStream(
                "/META-INF/desiredstate/tests/parser-test-fixture.yaml")) {
            var suite = PluginTestYamlParser.parse(is);
            var faultTest = suite.testCases().get(3);

            assertThat(faultTest.faultInjection()).isNotNull();
            assertThat(faultTest.faultInjection().failCount()).isEqualTo(3);
            assertThat(faultTest.faultInjection().error()).isEqualTo("Connection refused");
            assertThat(faultTest.faultInjection().action()).isEqualTo("provision");
        }
    }

    @Test
    void parsesValidateAction() throws Exception {
        try (var is = getClass().getResourceAsStream(
                "/META-INF/desiredstate/tests/parser-test-fixture.yaml")) {
            var suite = PluginTestYamlParser.parse(is);
            var validateTest = suite.testCases().get(2);

            assertThat(validateTest.action()).isEqualTo("validate");
            assertThat(validateTest.assertions().get("error-matches")).isEqualTo("name is required");
        }
    }
}
