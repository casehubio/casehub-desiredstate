package io.casehub.desiredstate.bridge.terraform;

import io.casehub.desiredstate.bridge.BridgeReadRequest;
import io.casehub.desiredstate.bridge.BridgeRequest;
import io.casehub.desiredstate.bridge.BridgeResult;
import io.casehub.desiredstate.bridge.BridgeStateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("cloud-integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GcpProviderIntegrationTest {

    private static final Logger log = Logger.getLogger(GcpProviderIntegrationTest.class.getName());
    private static final String TYPE_ID = "tf:hashicorp/google:google_storage_bucket";
    private static final String RESOURCE_ID = "gcs-test-1";
    private static final String TENANT_ID = "cloud-test-tenant";

    static TenantProviderRegistry registry;
    static TerraformBridgeProvider provider;
    static String bucketName;
    static Map<String, Object> provisionedState;

    @BeforeAll
    static void setup() {
        Assumptions.assumeTrue(
            System.getenv("GOOGLE_CREDENTIALS") != null
                && System.getenv("GOOGLE_PROJECT") != null,
            "GCP credentials not set — skipping cloud integration tests");

        var registryClient = new ProviderRegistryClient();
        var config = new ProviderConfig("hashicorp", "google", "6.14.0", null);
        var binary = registryClient.resolve(config);
        registry = new TenantProviderRegistry(
            () -> new ProviderProcessManager(new GoPluginHost(), config, binary.binaryPath()),
            new EnvCredentialResolver(),
            "gcp");
        provider = new TerraformBridgeProvider(registry);
        bucketName = "casehub-test-" + Long.toHexString(System.nanoTime()).substring(0, 12);
    }

    @AfterAll
    static void teardown() {
        if (registry == null) return;
        try {
            if (provisionedState != null) {
                var request = new BridgeRequest(TYPE_ID, RESOURCE_ID,
                    Map.of("name", bucketName, "location", "US",
                        "force_destroy", true),
                    TENANT_ID, provisionedState);
                provider.destroy(request);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Cleanup failed for bucket " + bucketName, e);
        } finally {
            registry.close();
        }
    }

    @Test
    @Order(1)
    void schemaDiscoveryIncludesStorageBucket() {
        var types = registry.discoverTypes();
        assertThat(types.stream().map(dt -> dt.nodeType().value()))
            .anyMatch(t -> t.contains("google_storage_bucket"));
    }

    @Test
    @Order(2)
    void provisionStorageBucket() {
        var request = new BridgeRequest(TYPE_ID, RESOURCE_ID,
            Map.of("name", bucketName, "location", "US",
                "force_destroy", true),
            TENANT_ID);
        var result = provider.apply(request);
        assertThat(result).isInstanceOf(BridgeResult.Success.class);

        provisionedState = ((BridgeResult.Success) result).outputState();
        assertThat(provisionedState).isNotEmpty();
    }

    @Test
    @Order(3)
    void readStateReturnsPresent() {
        Assumptions.assumeTrue(provisionedState != null, "Provision did not succeed");

        var request = new BridgeReadRequest(TYPE_ID, RESOURCE_ID, TENANT_ID,
            provisionedState,
            Map.of("name", bucketName, "location", "US",
                "force_destroy", true));
        var result = provider.readState(request);
        assertThat(result).isInstanceOf(BridgeStateResult.Found.class);

        var found = (BridgeStateResult.Found) result;
        assertThat(found.state()).containsEntry("status", "present");
    }

    @Test
    @Order(4)
    void deprovisionStorageBucket() {
        Assumptions.assumeTrue(provisionedState != null, "Provision did not succeed");

        var request = new BridgeRequest(TYPE_ID, RESOURCE_ID,
            Map.of("name", bucketName, "location", "US",
                "force_destroy", true),
            TENANT_ID, provisionedState);
        var result = provider.destroy(request);
        assertThat(result).isInstanceOf(BridgeResult.Success.class);

        provisionedState = null;
    }

    @Test
    @Order(5)
    void readStateAfterDestroyReturnsNotFound() {
        var request = new BridgeReadRequest(TYPE_ID, RESOURCE_ID, TENANT_ID,
            Map.of(), Map.of());
        var result = provider.readState(request);
        assertThat(result).isInstanceOf(BridgeStateResult.NotFound.class);
    }
}
