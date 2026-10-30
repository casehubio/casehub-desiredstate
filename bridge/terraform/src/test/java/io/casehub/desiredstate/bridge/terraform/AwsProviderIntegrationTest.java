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
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("cloud-integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AwsProviderIntegrationTest {

    private static final Logger log = Logger.getLogger(AwsProviderIntegrationTest.class.getName());
    private static final String TYPE_ID = "tf:hashicorp/aws:aws_ssm_parameter";
    private static final String RESOURCE_ID = "ssm-test-1";
    private static final String TENANT_ID = "cloud-test-tenant";

    static TenantProviderRegistry registry;
    static TerraformBridgeProvider provider;
    static String parameterName;
    static Map<String, Object> provisionedState;

    @BeforeAll
    static void setup() {
        Assumptions.assumeTrue(
            System.getenv("AWS_ACCESS_KEY_ID") != null
                && System.getenv("AWS_SECRET_ACCESS_KEY") != null
                && System.getenv("AWS_DEFAULT_REGION") != null,
            "AWS credentials not set — skipping cloud integration tests");

        var registryClient = new ProviderRegistryClient();
        var config = new ProviderConfig("hashicorp", "aws", "5.82.2", null);
        var binary = registryClient.resolve(config);
        registry = new TenantProviderRegistry(
            () -> new ProviderProcessManager(new GoPluginHost(), config, binary.binaryPath()),
            new EnvCredentialResolver(),
            "aws");
        provider = new TerraformBridgeProvider(registry);
        parameterName = "/casehub-test/" + UUID.randomUUID();
    }

    @AfterAll
    static void teardown() {
        if (registry == null) return;
        try {
            if (provisionedState != null) {
                var request = new BridgeRequest(TYPE_ID, RESOURCE_ID,
                    Map.of("name", parameterName, "type", "String", "value", "cleanup"),
                    TENANT_ID, provisionedState);
                provider.destroy(request);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Cleanup failed for " + parameterName, e);
        } finally {
            registry.close();
        }
    }

    @Test
    @Order(1)
    void schemaDiscoveryIncludesSsmParameter() {
        var types = registry.discoverTypes();
        assertThat(types.stream().map(dt -> dt.nodeType().value()))
            .anyMatch(t -> t.contains("aws_ssm_parameter"));
    }

    @Test
    @Order(2)
    void provisionSsmParameter() {
        var request = new BridgeRequest(TYPE_ID, RESOURCE_ID,
            Map.of("name", parameterName, "type", "String", "value", "test-value"),
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
            Map.of("name", parameterName, "type", "String", "value", "test-value"));
        var result = provider.readState(request);
        assertThat(result).isInstanceOf(BridgeStateResult.Found.class);

        var found = (BridgeStateResult.Found) result;
        assertThat(found.state()).containsEntry("status", "present");
    }

    @Test
    @Order(4)
    void deprovisionSsmParameter() {
        Assumptions.assumeTrue(provisionedState != null, "Provision did not succeed");

        var request = new BridgeRequest(TYPE_ID, RESOURCE_ID,
            Map.of("name", parameterName, "type", "String", "value", "test-value"),
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
