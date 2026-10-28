package io.casehub.desiredstate.bridge.terraform;

import io.casehub.desiredstate.bridge.*;
import io.casehub.desiredstate.bridge.runtime.InMemoryBridgeOutputStateStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RandomProviderIntegrationTest {

    static ProviderProcessManager processManager;
    static TerraformBridgeProvider provider;
    static InMemoryBridgeOutputStateStore stateStore;

    @BeforeAll
    static void setup() {
        var registryClient = new ProviderRegistryClient();
        var config = new ProviderConfig("hashicorp", "random", "3.6.3", null);
        var binary = registryClient.resolve(config);
        processManager = new ProviderProcessManager(
            new GoPluginHost(), config, binary.binaryPath());
        var registry = new TenantProviderRegistry(
            () -> processManager, null, null);
        provider = new TerraformBridgeProvider(registry);
        stateStore = new InMemoryBridgeOutputStateStore();
    }

    @AfterAll
    static void teardown() {
        if (processManager != null) processManager.shutdown();
    }

    @Test
    @Order(1)
    void zeroConfigSchemaDiscovery() {
        var types = processManager.discoverTypes();
        assertThat(types).isNotEmpty();
        var typeNames = types.stream().map(dt -> dt.nodeType().value()).toList();
        assertThat(typeNames).anyMatch(t -> t.contains("random_pet"));
        assertThat(typeNames).anyMatch(t -> t.contains("random_id"));
        assertThat(typeNames).anyMatch(t -> t.contains("random_string"));
    }

    @Test
    @Order(2)
    void specSchemaGeneratedForRandomPet() {
        var types = processManager.discoverTypes();
        var petType = types.stream()
            .filter(dt -> dt.nodeType().value().contains("random_pet"))
            .findFirst().orElseThrow();
        assertThat(petType.specSchema().fields()).isNotEmpty();
    }

    @Test
    @Order(3)
    void provisionRandomPet() {
        var request = new BridgeRequest(
            "tf:hashicorp/random:random_pet", "my-pet",
            Map.of("length", 2), "tenant-1");
        var result = provider.apply(request);
        assertThat(result).isInstanceOf(BridgeResult.Success.class);

        var success = (BridgeResult.Success) result;
        assertThat(success.outputState()).isNotEmpty();
        assertThat(success.outputState()).containsKey("id");
        stateStore.store("tenant-1", "my-pet", success.outputState());
    }

    @Test
    @Order(4)
    void readRandomPetStatePresent() {
        var previousState = stateStore.load("tenant-1", "my-pet").orElseThrow();
        var readRequest = new BridgeReadRequest(
            "tf:hashicorp/random:random_pet", "my-pet", "tenant-1",
            previousState, Map.of("length", 2));
        var result = provider.readState(readRequest);
        assertThat(result).isInstanceOf(BridgeStateResult.Found.class);

        var found = (BridgeStateResult.Found) result;
        assertThat(found.state()).containsKey("id");
    }

    @Test
    @Order(5)
    void deprovisionRandomPet() {
        var previousState = stateStore.load("tenant-1", "my-pet").orElseThrow();
        var request = new BridgeRequest(
            "tf:hashicorp/random:random_pet", "my-pet",
            Map.of("length", 2), "tenant-1", previousState);
        var result = provider.destroy(request);
        assertThat(result).isInstanceOf(BridgeResult.Success.class);
        stateStore.remove("tenant-1", "my-pet");
    }

    @Test
    @Order(6)
    void readAfterDeprovisionReturnsNotFound() {
        var readRequest = new BridgeReadRequest(
            "tf:hashicorp/random:random_pet", "my-pet", "tenant-1",
            Map.of(), Map.of());
        var result = provider.readState(readRequest);
        assertThat(result).isInstanceOf(BridgeStateResult.NotFound.class);
    }

    @Test
    @Order(7)
    void supportedTypesFromSchemaDiscovery() {
        var types = provider.supportedTypes();
        assertThat(types).anyMatch(t -> t.contains("random_pet"));
        assertThat(types).anyMatch(t -> t.contains("random_id"));
    }
}
