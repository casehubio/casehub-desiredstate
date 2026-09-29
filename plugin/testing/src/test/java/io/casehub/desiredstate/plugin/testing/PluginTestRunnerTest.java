package io.casehub.desiredstate.plugin.testing;

import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.plugin.model.PluginModel;
import io.casehub.desiredstate.plugin.model.PluginParser;
import io.casehub.desiredstate.plugin.runtime.PluginDescriptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PluginTestRunnerTest {

    private static PluginDescriptor descriptor;

    @BeforeAll
    static void loadPlugin() throws IOException {
        try (var is = PluginTestRunnerTest.class.getResourceAsStream(
                "/META-INF/desiredstate/plugins/mock-resource.yaml")) {
            PluginModel model = PluginParser.parse(is);
            descriptor = toDescriptor(model);
        }
    }

    @Test
    void provisionSucceeds() {
        var runner = new PluginTestRunner(descriptor, Map.of());
        var result = runner.runProvision(Map.of("name", "app1", "status-code", 200));
        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    }

    @Test
    void deprovisionSucceeds() {
        var runner = new PluginTestRunner(descriptor, Map.of());
        var result = runner.runDeprovision(Map.of("name", "app1", "status-code", 200));
        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
    }

    @Test
    void actualStatePresent() {
        var runner = new PluginTestRunner(descriptor, Map.of());
        var status = runner.runActualState(Map.of("name", "app1", "status-code", 200));
        assertThat(status).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void actualStateAbsent() {
        var runner = new PluginTestRunner(descriptor, Map.of());
        var status = runner.runActualState(Map.of("name", "gone", "status-code", 404));
        assertThat(status).isEqualTo(NodeStatus.ABSENT);
    }

    @Test
    void actualStateDrifted() {
        var runner = new PluginTestRunner(descriptor, Map.of());
        var status = runner.runActualState(Map.of("name", "partial", "status-code", 206));
        assertThat(status).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void faultInjectionCausesFailure() {
        var runner = new PluginTestRunner(descriptor, Map.of());
        var result = runner.runProvisionWithFaultInjection(
            Map.of("name", "app1", "status-code", 200), 1, "injected failure");
        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
        assertThat(((ProvisionResult.Failed) result).reason()).contains("injected failure");
    }

    @Test
    void infraBindingsResolved() {
        var runner = new PluginTestRunner(descriptor, Map.of());
        var resolved = runner.resolveInfraBindings(
            Map.of("url", "${wiremock.url}/api", "count", 5),
            Map.of("wiremock.url", "http://localhost:8080"));
        assertThat(resolved.get("url")).isEqualTo("http://localhost:8080/api");
        assertThat(resolved.get("count")).isEqualTo(5);
    }

    private static PluginDescriptor toDescriptor(PluginModel model) {
        return new PluginDescriptor(
            model.header().type(), model.header().version(),
            Duration.ofSeconds(30), Map.of(), model.spec(),
            model.actualStateSteps(),
            model.provisioner().provisionSteps(),
            model.provisioner().deprovisionSteps(),
            model.faultPolicies(), model.cbr(), model.ras());
    }
}
