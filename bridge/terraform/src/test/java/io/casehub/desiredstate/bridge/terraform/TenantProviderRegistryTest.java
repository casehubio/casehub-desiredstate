package io.casehub.desiredstate.bridge.terraform;

import io.casehub.desiredstate.bridge.PoolConfig;
import io.casehub.platform.api.credentials.CredentialResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void tenantsWithSameCredentialsShareProcess() {
        var tracker = new Tracker();
        CredentialResolver resolver = ref -> Map.of("access_key", "AKIA");
        registry = new TenantProviderRegistry(
            () -> createMockManager(tracker), resolver, "my-creds", PoolConfig.DEFAULT);

        var protocol1 = registry.getProtocol("tenant-1");
        var protocol2 = registry.getProtocol("tenant-2");

        assertThat(protocol1).isSameAs(protocol2);
        assertThat(tracker.configureCount.get()).isEqualTo(1);
        assertThat(registry.poolSize()).isEqualTo(1);
    }

    @Test
    void tenantsWithDifferentCredentialsGetDifferentProcesses() {
        var tracker = new Tracker();
        var callCount = new AtomicInteger();
        CredentialResolver resolver = ref -> {
            int call = callCount.getAndIncrement();
            return call == 0 ? Map.of("access_key", "AKIA-1") : Map.of("access_key", "AKIA-2");
        };
        registry = new TenantProviderRegistry(
            () -> createMockManager(tracker), resolver, "my-creds", PoolConfig.DEFAULT);

        var protocol1 = registry.getProtocol("tenant-1");
        var protocol2 = registry.getProtocol("tenant-2");

        assertThat(protocol1).isNotSameAs(protocol2);
        assertThat(tracker.configureCount.get()).isEqualTo(2);
        assertThat(registry.poolSize()).isEqualTo(2);
    }

    @Test
    void evictRemovesRoutingButKeepsSharedProcess() {
        var tracker = new Tracker();
        CredentialResolver resolver = ref -> Map.of("access_key", "AKIA");
        registry = new TenantProviderRegistry(
            () -> createMockManager(tracker), resolver, "my-creds", PoolConfig.DEFAULT);

        registry.getProtocol("tenant-1");
        registry.getProtocol("tenant-2");

        registry.evict("tenant-1");

        assertThat(tracker.shutdownCount.get()).isZero();
        assertThat(registry.poolSize()).isEqualTo(1);
    }

    @Test
    void evictLastTenantLeavesProcessForIdleEviction() {
        var tracker = new Tracker();
        CredentialResolver resolver = ref -> Map.of("access_key", "AKIA");
        registry = new TenantProviderRegistry(
            () -> createMockManager(tracker), resolver, "my-creds", PoolConfig.DEFAULT);

        registry.getProtocol("tenant-1");
        registry.evict("tenant-1");

        assertThat(tracker.shutdownCount.get()).isZero();
        assertThat(registry.poolSize()).isEqualTo(1);
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
    void closeShutdownsAllPooledProcesses() {
        var tracker = new Tracker();
        CredentialResolver resolver = ref -> Map.of("access_key", "AKIA");
        registry = new TenantProviderRegistry(
            () -> createMockManager(tracker), resolver, "my-creds");

        registry.getProtocol("tenant-1");
        registry.getProtocol("tenant-2");
        registry.close();

        assertThat(tracker.shutdownCount.get()).isEqualTo(1);
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

    @Test
    void maxPoolSizeEvictsLruWhenFull() {
        var tracker = new Tracker();
        var callCount = new AtomicInteger();
        CredentialResolver resolver = ref -> Map.of("key", "value-" + callCount.getAndIncrement());
        var poolConfig = new PoolConfig(2, Duration.ofMinutes(5), Duration.ofSeconds(30));
        registry = new TenantProviderRegistry(
            () -> createMockManager(tracker), resolver, "my-creds", poolConfig);

        registry.getProtocol("tenant-1");
        registry.evict("tenant-1");
        registry.getProtocol("tenant-2");
        registry.getProtocol("tenant-3");

        assertThat(tracker.shutdownCount.get()).isEqualTo(1);
        assertThat(registry.poolSize()).isEqualTo(2);
    }

    @Test
    void poolExhaustedWhenAllEntriesHaveActiveRefs() {
        var tracker = new Tracker();
        var callCount = new AtomicInteger();
        CredentialResolver resolver = ref -> Map.of("key", "value-" + callCount.getAndIncrement());
        var poolConfig = new PoolConfig(2, Duration.ofMinutes(5), Duration.ofMillis(100));
        registry = new TenantProviderRegistry(
            () -> createMockManager(tracker), resolver, "my-creds", poolConfig);

        registry.getProtocol("tenant-1");
        registry.getProtocol("tenant-2");

        assertThatThrownBy(() -> registry.getProtocol("tenant-3"))
            .isInstanceOf(ProviderPoolExhaustedException.class);
    }

    @Test
    void idleEvictionDoesNotRemoveActiveRefEntry() throws InterruptedException {
        var                tracker    = new Tracker();
        CredentialResolver resolver   = ref -> Map.of("access_key", "AKIA");
        var                poolConfig = new PoolConfig(10, Duration.ofMillis(50), Duration.ofSeconds(30));
        registry = new TenantProviderRegistry(
                () -> createMockManager(tracker), resolver, "my-creds", poolConfig);

        registry.getProtocol("tenant-1");

        Thread.sleep(100);
        assertThat(registry.poolSize()).isEqualTo(1);
    }

    @Test
    void concurrentGetProtocolDoesNotDuplicateProcessCreation() throws Exception {
        var                tracker  = new Tracker();
        CredentialResolver resolver = ref -> Map.of("access_key", "AKIA");
        registry = new TenantProviderRegistry(
                () -> createMockManager(tracker), resolver, "my-creds", PoolConfig.DEFAULT);

        int threadCount = 10;
        var latch       = new java.util.concurrent.CountDownLatch(1);
        var done        = new java.util.concurrent.CountDownLatch(threadCount);
        var protocols   = new java.util.concurrent.ConcurrentLinkedQueue<TerraformProtocol>();

        for (int i = 0; i < threadCount; i++) {
            String tenantId = "tenant-" + i;
            new Thread(() -> {
                try {
                    latch.await();
                    protocols.add(registry.getProtocol(tenantId));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        latch.countDown();
        done.await(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(protocols).hasSize(threadCount);
        var first = protocols.peek();
        assertThat(protocols).allSatisfy(p -> assertThat(p).isSameAs(first));
        assertThat(tracker.configureCount.get()).isEqualTo(1);
        assertThat(registry.poolSize()).isEqualTo(1);
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
