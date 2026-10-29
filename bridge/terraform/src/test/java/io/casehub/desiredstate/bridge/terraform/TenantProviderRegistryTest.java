package io.casehub.desiredstate.bridge.terraform;

import io.casehub.platform.api.credentials.CredentialResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TenantProviderRegistryTest {

    private TenantProviderRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) registry.close();
    }

    @Test
    void getProtocolCreatesProcessOnFirstAccess() {
        var tracker = new Tracker();
        CredentialResolver resolver = ref -> Map.of("access_key", "AKIA", "secret_key", "secret");
        registry = new TenantProviderRegistry(
            () -> createMockManager(tracker), resolver, "my-creds");

        var protocol = registry.getProtocol("tenant-1");

        assertThat(protocol).isNotNull();
        assertThat(tracker.configureCount.get()).isEqualTo(1);
    }

    @Test
    void getProtocolReturnsCachedOnSecondAccess() {
        var tracker = new Tracker();
        CredentialResolver resolver = ref -> Map.of("access_key", "AKIA");
        registry = new TenantProviderRegistry(
            () -> createMockManager(tracker), resolver, "my-creds");

        var protocol1 = registry.getProtocol("tenant-1");
        var protocol2 = registry.getProtocol("tenant-1");

        assertThat(protocol1).isSameAs(protocol2);
        assertThat(tracker.configureCount.get()).isEqualTo(1);
    }

    @Test
    void differentTenantsGetDifferentProcesses() {
        var tracker = new Tracker();
        CredentialResolver resolver = ref -> Map.of("access_key", "AKIA");
        registry = new TenantProviderRegistry(
            () -> createMockManager(tracker), resolver, "my-creds");

        var protocol1 = registry.getProtocol("tenant-1");
        var protocol2 = registry.getProtocol("tenant-2");

        assertThat(protocol1).isNotSameAs(protocol2);
        assertThat(tracker.configureCount.get()).isEqualTo(2);
    }

    @Test
    void evictShutdownsAndRemovesTenantProcess() {
        var tracker = new Tracker();
        CredentialResolver resolver = ref -> Map.of("access_key", "AKIA");
        registry = new TenantProviderRegistry(
            () -> createMockManager(tracker), resolver, "my-creds");

        registry.getProtocol("tenant-1");
        assertThat(tracker.shutdownCount.get()).isZero();

        registry.evict("tenant-1");
        assertThat(tracker.shutdownCount.get()).isEqualTo(1);

        registry.getProtocol("tenant-1");
        assertThat(tracker.configureCount.get()).isEqualTo(2);
    }

    @Test
    void noCredentialRefSkipsConfigure() {
        var tracker = new Tracker();
        registry = new TenantProviderRegistry(
            () -> createMockManager(tracker), null, null);

        registry.getProtocol("tenant-1");

        assertThat(tracker.configureCount.get()).isZero();
    }

    @Test
    void closeShutdownsAllTenantProcesses() {
        var tracker = new Tracker();
        CredentialResolver resolver = ref -> Map.of("access_key", "AKIA");
        registry = new TenantProviderRegistry(
            () -> createMockManager(tracker), resolver, "my-creds");

        registry.getProtocol("tenant-1");
        registry.getProtocol("tenant-2");
        registry.close();

        assertThat(tracker.shutdownCount.get()).isEqualTo(2);
    }

    @Test
    void discoverTypesUsesSchemaOnlyProcess() {
        var tracker = new Tracker();
        registry = new TenantProviderRegistry(
            () -> createMockManager(tracker), null, null);

        var types = registry.discoverTypes();

        assertThat(types).isNotEmpty();
        assertThat(tracker.configureCount.get()).isZero();
    }

    @Test
    void evictNonExistentTenantIsNoOp() {
        var tracker = new Tracker();
        registry = new TenantProviderRegistry(
            () -> createMockManager(tracker), null, null);

        registry.evict("non-existent");

        assertThat(tracker.shutdownCount.get()).isZero();
    }

    private ProviderProcessManager createMockManager(Tracker tracker) {
        var protocol = new TrackingMockProtocol(tracker);
        return new TerraformBridgeProviderTest.MockProviderProcessManager(protocol) {
            @Override
            public void shutdown() {
                tracker.shutdownCount.incrementAndGet();
            }
        };
    }

    static class Tracker {
        final AtomicInteger configureCount = new AtomicInteger();
        final AtomicInteger shutdownCount = new AtomicInteger();
    }

    static class TrackingMockProtocol implements TerraformProtocol {
        private final Tracker tracker;

        TrackingMockProtocol(Tracker tracker) {
            this.tracker = tracker;
        }

        @Override
        public ProviderSchema getSchema() {
            return new ProviderSchema(Map.of(
                "mock_resource", new ResourceSchema(Map.of())));
        }

        @Override
        public void configure(Map<String, Object> config) {
            tracker.configureCount.incrementAndGet();
        }

        @Override
        public PlanResponse plan(String resourceType, Map<String, Object> priorState,
                                  Map<String, Object> proposedState) {
            return new PlanResponse(Map.of(), new byte[0], false, List.of());
        }

        @Override
        public ApplyResponse apply(String resourceType, Map<String, Object> priorState,
                                    byte[] plannedPrivate) {
            return new ApplyResponse(Map.of(), new byte[0], List.of());
        }

        @Override
        public ReadResponse read(String resourceType, Map<String, Object> currentState) {
            return new ReadResponse(Map.of(), new byte[0], List.of());
        }

        @Override
        public void close() {}

        @Override
        public ImportResponse importResource(String resourceType, String id) {
            return new ImportResponse(Map.of(), new byte[0], List.of());
        }
    }
}
