package io.casehub.desiredstate.bridge.terraform;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NullProviderIntegrationTest {

    static ProviderProcessManager processManager;
    static TerraformBridgeProvider provider;

    @BeforeAll
    static void setup() {
        var registryClient = new ProviderRegistryClient();
        var config = new ProviderConfig("hashicorp", "null", "3.2.3", null);
        var binary = registryClient.resolve(config);
        processManager = new ProviderProcessManager(
            new GoPluginHost(), config, binary.binaryPath());
        var registry = new TenantProviderRegistry(
            () -> processManager, null, null);
        provider = new TerraformBridgeProvider(registry);
    }

    @AfterAll
    static void teardown() {
        if (processManager != null) processManager.shutdown();
    }

    @Test
    @Order(1)
    void handshakeSucceeds() {
        var protocol = processManager.getProtocol();
        assertThat(protocol).isNotNull();
    }

    @Test
    @Order(2)
    void schemaDiscoveryReturnsNullResource() {
        var types = processManager.discoverTypes();
        assertThat(types).isNotEmpty();
        assertThat(types.stream().map(dt -> dt.nodeType().value()))
            .anyMatch(t -> t.contains("null_resource"));
    }

    @Test
    @Order(3)
    void schemaHasSpecFields() {
        var types = processManager.discoverTypes();
        var nullRes = types.stream()
            .filter(dt -> dt.nodeType().value().contains("null_resource"))
            .findFirst().orElseThrow();
        assertThat(nullRes.specSchema()).isNotNull();
    }

    @Test
    @Order(4)
    void provisionAndDestroyNullResource() {
        var request = new io.casehub.desiredstate.bridge.BridgeRequest(
            "tf:hashicorp/null:null_resource", "test-1",
            java.util.Map.of("triggers", java.util.Map.of("key", "value")),
            "tenant-1");

        var result = provider.apply(request);
        assertThat(result).isInstanceOf(io.casehub.desiredstate.bridge.BridgeResult.Success.class);

        var success = (io.casehub.desiredstate.bridge.BridgeResult.Success) result;
        var destroyRequest = new io.casehub.desiredstate.bridge.BridgeRequest(
            "tf:hashicorp/null:null_resource", "test-1",
            java.util.Map.of("triggers", java.util.Map.of("key", "value")),
            "tenant-1", success.outputState());

        var destroyResult = provider.destroy(destroyRequest);
        assertThat(destroyResult).isInstanceOf(io.casehub.desiredstate.bridge.BridgeResult.Success.class);
    }
}
