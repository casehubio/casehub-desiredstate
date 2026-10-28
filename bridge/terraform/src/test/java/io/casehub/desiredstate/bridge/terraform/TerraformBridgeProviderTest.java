package io.casehub.desiredstate.bridge.terraform;

import io.casehub.desiredstate.bridge.BridgeReadRequest;
import io.casehub.desiredstate.bridge.BridgeRequest;
import io.casehub.desiredstate.bridge.BridgeResult;
import io.casehub.desiredstate.bridge.BridgeStateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TerraformBridgeProviderTest {

    private MockTerraformProtocol mockProtocol;
    private TerraformBridgeProvider provider;

    @BeforeEach
    void setup() {
        mockProtocol = new MockTerraformProtocol();
        var registry = new MockTenantProviderRegistry(mockProtocol);
        provider = new TerraformBridgeProvider(registry);
    }

    @Test
    void applyCallsPlanThenApply() {
        mockProtocol.planResponse = new PlanResponse(
            Map.of("id", "i-123"), new byte[]{1, 2}, false, List.of());
        mockProtocol.applyResponse = new ApplyResponse(
            Map.of("id", "i-123", "arn", "arn:aws:ec2:..."), new byte[0], List.of());

        var request = new BridgeRequest("tf:hashicorp/aws:aws_instance", "web-1",
            Map.of("ami", "ami-123"), "tenant-1");
        var result = provider.apply(request);

        assertThat(result).isInstanceOf(BridgeResult.Success.class);
        var success = (BridgeResult.Success) result;
        assertThat(success.outputState()).containsEntry("id", "i-123");
        assertThat(success.outputState()).containsEntry("arn", "arn:aws:ec2:...");
    }

    @Test
    void applyReturnsFailedOnPlanError() {
        mockProtocol.planResponse = new PlanResponse(
            Map.of(), new byte[0], false,
            List.of(new TfDiagnostic(TfDiagnostic.Severity.ERROR, "Invalid config", "Missing AMI")));

        var request = new BridgeRequest("tf:hashicorp/aws:aws_instance", "web-1",
            Map.of(), "tenant-1");
        var result = provider.apply(request);

        assertThat(result).isInstanceOf(BridgeResult.Failed.class);
        var failed = (BridgeResult.Failed) result;
        assertThat(failed.reason()).contains("Invalid config");
    }

    @Test
    void destroyCallsPlanWithNullProposed() {
        mockProtocol.planResponse = new PlanResponse(
            Map.of(), new byte[]{3}, false, List.of());
        mockProtocol.applyResponse = new ApplyResponse(
            Map.of(), new byte[0], List.of());

        var request = new BridgeRequest("tf:hashicorp/aws:aws_instance", "web-1",
            Map.of("ami", "ami-123"), "tenant-1", Map.of("id", "i-123"));
        var result = provider.destroy(request);

        assertThat(result).isInstanceOf(BridgeResult.Success.class);
        assertThat(mockProtocol.lastPlanProposedState).isNull();
    }

    @Test
    void readStateReturnsFoundWithPresent() {
        mockProtocol.readResponse = new ReadResponse(
            Map.of("id", "i-123", "ami", "ami-123"), new byte[0], List.of());
        mockProtocol.planResponse = new PlanResponse(
            Map.of("id", "i-123", "ami", "ami-123"), new byte[0], false, List.of());

        var request = new BridgeReadRequest("tf:hashicorp/aws:aws_instance", "web-1",
            "tenant-1", Map.of("id", "i-123"), Map.of("ami", "ami-123"));
        var result = provider.readState(request);

        assertThat(result).isInstanceOf(BridgeStateResult.Found.class);
        var found = (BridgeStateResult.Found) result;
        assertThat(found.state()).containsEntry("status", "present");
    }

    @Test
    void readStateReturnsFoundWithDrifted() {
        mockProtocol.readResponse = new ReadResponse(
            Map.of("id", "i-123", "ami", "ami-old"), new byte[0], List.of());
        mockProtocol.planResponse = new PlanResponse(
            Map.of("id", "i-123", "ami", "ami-new"), new byte[0], true, List.of());

        var request = new BridgeReadRequest("tf:hashicorp/aws:aws_instance", "web-1",
            "tenant-1", Map.of("id", "i-123"), Map.of("ami", "ami-new"));
        var result = provider.readState(request);

        assertThat(result).isInstanceOf(BridgeStateResult.Found.class);
        var found = (BridgeStateResult.Found) result;
        assertThat(found.state()).containsEntry("status", "drifted");
    }

    @Test
    void readStateReturnsNotFoundWhenEmpty() {
        mockProtocol.readResponse = new ReadResponse(Map.of(), new byte[0], List.of());

        var request = new BridgeReadRequest("tf:hashicorp/aws:aws_instance", "web-1",
            "tenant-1", Map.of("id", "i-123"), Map.of());
        var result = provider.readState(request);

        assertThat(result).isInstanceOf(BridgeStateResult.NotFound.class);
    }

    @Test
    void stripNamespaceExtractsResourceType() {
        mockProtocol.planResponse = new PlanResponse(
            Map.of(), new byte[0], false, List.of());
        mockProtocol.applyResponse = new ApplyResponse(
            Map.of(), new byte[0], List.of());

        var request = new BridgeRequest("tf:hashicorp/aws:aws_instance", "web-1",
            Map.of(), "tenant-1");
        provider.apply(request);

        assertThat(mockProtocol.lastPlanResourceType).isEqualTo("aws_instance");
    }

    @Test
    void supportedTypesDiscoveredFromProcessManager() {
        var types = provider.supportedTypes();
        assertThat(types).contains("tf:mock/test:mock_resource");
    }


    @Test
    void applyRetriesOnAuthenticationError() {
        var callCount = new java.util.concurrent.atomic.AtomicInteger();
        var retryProtocol = new MockTerraformProtocol() {
            @Override
            public PlanResponse plan(String resourceType, Map<String, Object> priorState,
                                     Map<String, Object> proposedState) {
                lastPlanResourceType  = resourceType;
                lastPlanProposedState = proposedState;
                if (callCount.incrementAndGet() == 1) {
                    return new PlanResponse(Map.of(), new byte[0], false,
                                            List.of(new TfDiagnostic(TfDiagnostic.Severity.ERROR,
                                                                     "unauthorized", "Credentials expired")));
                }
                return planResponse;
            }
        };
        retryProtocol.planResponse  = new PlanResponse(
                Map.of("id", "i-123"), new byte[]{1}, false, List.of());
        retryProtocol.applyResponse = new ApplyResponse(
                Map.of("id", "i-123"), new byte[0], List.of());

        var registry      = new MockTenantProviderRegistry(retryProtocol);
        var retryProvider = new TerraformBridgeProvider(registry);

        var request = new BridgeRequest("tf:hashicorp/aws:aws_instance", "web-1",
                                        Map.of("ami", "ami-123"), "tenant-1");
        var result = retryProvider.apply(request);

        assertThat(result).isInstanceOf(BridgeResult.Success.class);
        assertThat(registry.evictCount).isEqualTo(1);
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void applyPropagatesSecondAuthFailure() {
        var retryProtocol = new MockTerraformProtocol() {
            @Override
            public PlanResponse plan(String resourceType, Map<String, Object> priorState,
                                     Map<String, Object> proposedState) {
                return new PlanResponse(Map.of(), new byte[0], false,
                                        List.of(new TfDiagnostic(TfDiagnostic.Severity.ERROR,
                                                                 "unauthorized", "Bad credentials")));
            }
        };

        var registry      = new MockTenantProviderRegistry(retryProtocol);
        var retryProvider = new TerraformBridgeProvider(registry);

        var request = new BridgeRequest("tf:hashicorp/aws:aws_instance", "web-1",
                                        Map.of("ami", "ami-123"), "tenant-1");
        var result = retryProvider.apply(request);

        assertThat(result).isInstanceOf(BridgeResult.Failed.class);
        assertThat(registry.evictCount).isEqualTo(1);
    }

    static class MockTerraformProtocol implements TerraformProtocol {
        PlanResponse planResponse;
        ApplyResponse applyResponse;
        ReadResponse readResponse;
        String lastPlanResourceType;
        Map<String, Object> lastPlanProposedState;

        @Override
        public ProviderSchema getSchema() {
            return new ProviderSchema(Map.of(
                "mock_resource", new ResourceSchema(Map.of())
            ));
        }

        @Override
        public void configure(Map<String, Object> config) {}

        @Override
        public PlanResponse plan(String resourceType, Map<String, Object> priorState,
                                  Map<String, Object> proposedState) {
            lastPlanResourceType = resourceType;
            lastPlanProposedState = proposedState;
            return planResponse;
        }

        @Override
        public ApplyResponse apply(String resourceType, Map<String, Object> priorState,
                                    byte[] plannedPrivate) {
            return applyResponse;
        }

        @Override
        public ReadResponse read(String resourceType, Map<String, Object> currentState) {
            return readResponse;
        }

        @Override
        public void close() {}
    }

    static class MockProviderProcessManager extends ProviderProcessManager {
        private final TerraformProtocol protocol;

        MockProviderProcessManager(TerraformProtocol protocol) {
            super(new GoPluginHost(), new ProviderConfig("mock", "test", "1.0.0", null),
                  java.nio.file.Path.of("/dev/null"));
            this.protocol = protocol;
        }

        @Override
        public TerraformProtocol getProtocol() {
            return protocol;
        }

        @Override
        public java.util.List<DiscoveredType> discoverTypes() {
            var schema = protocol.getSchema();
            return SchemaTranslator.translate("mock", "test", schema);
        }
    }

    static class MockTenantProviderRegistry extends TenantProviderRegistry {
        private final TerraformProtocol protocol;
        int evictCount = 0;

        MockTenantProviderRegistry(TerraformProtocol protocol) {
            super(null, null, null);
            this.protocol = protocol;
        }

        @Override
        public TerraformProtocol getProtocol(String tenancyId) {
            return protocol;
        }

        @Override
        public java.util.List<DiscoveredType> discoverTypes() {
            var schema = protocol.getSchema();
            return SchemaTranslator.translate("mock", "test", schema);
        }

        @Override
        public void evict(String tenancyId) {
            evictCount++;
        }

        @Override
        public void close() {}
    }

}
