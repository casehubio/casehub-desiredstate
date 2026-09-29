package io.casehub.desiredstate.plugin.testing;

import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.plugin.testing.PluginTestAssertions.ActionResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginTestAssertionsTest {

    @Test
    void provisionSuccessMatches() {
        var result = ActionResult.ofProvision(new ProvisionResult.Success());
        assertThatCode(() ->
            PluginTestAssertions.evaluate(Map.of("provision", "success"), result))
            .doesNotThrowAnyException();
    }

    @Test
    void provisionFailedMatches() {
        var result = ActionResult.ofProvision(new ProvisionResult.Failed("timeout"));
        assertThatCode(() ->
            PluginTestAssertions.evaluate(Map.of("provision", "failed"), result))
            .doesNotThrowAnyException();
    }

    @Test
    void provisionMismatchThrows() {
        var result = ActionResult.ofProvision(new ProvisionResult.Failed("timeout"));
        assertThatThrownBy(() ->
            PluginTestAssertions.evaluate(Map.of("provision", "success"), result))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Expected")
            .hasMessageContaining("success");
    }

    @Test
    void actualStateMatches() {
        var result = ActionResult.ofActualState(NodeStatus.PRESENT);
        assertThatCode(() ->
            PluginTestAssertions.evaluate(Map.of("actual-state", "PRESENT"), result))
            .doesNotThrowAnyException();
    }

    @Test
    void actualStateMismatchThrows() {
        var result = ActionResult.ofActualState(NodeStatus.ABSENT);
        assertThatThrownBy(() ->
            PluginTestAssertions.evaluate(Map.of("actual-state", "PRESENT"), result))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("ABSENT");
    }

    @Test
    void errorMatchesSubstring() {
        var result = ActionResult.ofProvision(new ProvisionResult.Failed("Connection refused: host=localhost"));
        assertThatCode(() ->
            PluginTestAssertions.evaluate(
                Map.of("provision", "failed", "error-matches", "Connection refused"), result))
            .doesNotThrowAnyException();
    }

    @Test
    void errorMatchesMismatchThrows() {
        var result = ActionResult.ofProvision(new ProvisionResult.Failed("Timeout"));
        assertThatThrownBy(() ->
            PluginTestAssertions.evaluate(Map.of("error-matches", "Connection refused"), result))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Timeout");
    }

    @Test
    void deprovisionSuccessMatches() {
        var result = ActionResult.ofDeprovision(new DeprovisionResult.Success());
        assertThatCode(() ->
            PluginTestAssertions.evaluate(Map.of("deprovision", "success"), result))
            .doesNotThrowAnyException();
    }

    @Test
    void validationErrorMatches() {
        var result = ActionResult.ofValidationError("name is required");
        assertThatCode(() ->
            PluginTestAssertions.evaluate(Map.of("error-matches", "name is required"), result))
            .doesNotThrowAnyException();
    }
}
