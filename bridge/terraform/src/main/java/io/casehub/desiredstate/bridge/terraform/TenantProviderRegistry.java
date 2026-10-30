package io.casehub.desiredstate.bridge.terraform;

import io.casehub.desiredstate.bridge.PoolConfig;
import io.casehub.platform.api.credentials.CredentialResolver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class TenantProviderRegistry implements AutoCloseable {

    private final ConcurrentHashMap<String, PoolEntry> pool = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> routing = new ConcurrentHashMap<>();
    private final ReentrantLock poolLock = new ReentrantLock();
    private final ProcessManagerFactory factory;
    private final CredentialResolver credentialResolver;
    private final String credentialRef;
    private final PoolConfig poolConfig;
    private final ScheduledExecutorService evictionScheduler;
    private final ScheduledFuture<?> evictionTask;
    private volatile ProviderProcessManager schemaOnlyManager;
    private final Object schemaLock = new Object();

    public TenantProviderRegistry(ProcessManagerFactory factory,
                                   CredentialResolver credentialResolver,
                                   String credentialRef,
                                   PoolConfig poolConfig) {
        this.factory = factory;
        this.credentialResolver = credentialResolver;
        this.credentialRef = credentialRef;
        this.poolConfig = poolConfig != null ? poolConfig : PoolConfig.DEFAULT;
        this.evictionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "provider-pool-evictor");
            t.setDaemon(true);
            return t;
        });
        this.evictionTask = evictionScheduler.scheduleAtFixedRate(
            this::evictIdle, 30, 30, TimeUnit.SECONDS);
    }

    public TenantProviderRegistry(ProcessManagerFactory factory,
                                   CredentialResolver credentialResolver,
                                   String credentialRef) {
        this(factory, credentialResolver, credentialRef, null);
    }

    public TerraformProtocol getProtocol(String tenancyId) {
        Map<String, String> credentials = resolveCredentials();
        String configHash = ConfigHasher.hash(credentials);

        String existingHash = routing.get(tenancyId);
        if (existingHash != null && existingHash.equals(configHash)) {
            PoolEntry entry = pool.get(configHash);
            if (entry != null) {
                entry.touch();
                return entry.manager().getProtocol();
            }
        }

        poolLock.lock();
        try {
            existingHash = routing.get(tenancyId);
            if (existingHash != null && existingHash.equals(configHash)) {
                PoolEntry entry = pool.get(configHash);
                if (entry != null) {
                    entry.touch();
                    return entry.manager().getProtocol();
                }
            }

            if (existingHash != null && !existingHash.equals(configHash)) {
                routing.remove(tenancyId);
                PoolEntry oldEntry = pool.get(existingHash);
                if (oldEntry != null) {
                    oldEntry.decrementRef();
                }
            }

            PoolEntry entry = pool.get(configHash);
            if (entry == null) {
                ensureCapacity();
                var manager = factory.create();
                configureWithCredentials(manager, credentials);
                entry = new PoolEntry(manager, configHash);
                pool.put(configHash, entry);
            }

            routing.put(tenancyId, configHash);
            entry.incrementRef();
            entry.touch();
            return entry.manager().getProtocol();
        } finally {
            poolLock.unlock();
        }
    }

    public List<DiscoveredType> discoverTypes() {
        return getSchemaOnlyManager().discoverTypes();
    }

    public void evict(String tenancyId) {
        poolLock.lock();
        try {
            String configHash = routing.remove(tenancyId);
            if (configHash != null) {
                PoolEntry entry = pool.get(configHash);
                if (entry != null) {
                    entry.decrementRef();
                }
            }
        } finally {
            poolLock.unlock();
        }
    }

    public int poolSize() {
        return pool.size();
    }

    @Override
    public void close() {
        evictionTask.cancel(false);
        evictionScheduler.shutdownNow();
        pool.values().forEach(e -> e.manager().shutdown());
        pool.clear();
        routing.clear();
        synchronized (schemaLock) {
            if (schemaOnlyManager != null) {
                schemaOnlyManager.shutdown();
                schemaOnlyManager = null;
            }
        }
    }

    private Map<String, String> resolveCredentials() {
        if (credentialRef != null && credentialResolver != null) {
            return credentialResolver.resolve(credentialRef);
        }
        return Map.of();
    }

    private void configureWithCredentials(ProviderProcessManager manager,
                                           Map<String, String> credentials) {
        if (!credentials.isEmpty()) {
            manager.getProtocol().configure(new HashMap<>(credentials));
        }
    }

    private void ensureCapacity() {
        if (pool.size() < poolConfig.maxProcesses()) return;

        PoolEntry lru = null;
        for (PoolEntry entry : pool.values()) {
            if (entry.refCount() == 0) {
                if (lru == null || entry.lastAccessNanos() < lru.lastAccessNanos()) {
                    lru = entry;
                }
            }
        }
        if (lru != null) {
            pool.remove(lru.configHash());
            lru.manager().shutdown();
            return;
        }

        poolLock.unlock();
        try {
            long deadline = System.nanoTime() + poolConfig.checkoutTimeout().toNanos();
            while (System.nanoTime() < deadline) {
                for (PoolEntry entry : pool.values()) {
                    if (entry.refCount() == 0) {
                        poolLock.lock();
                        if (entry.refCount() == 0 && pool.containsKey(entry.configHash())) {
                            pool.remove(entry.configHash());
                            entry.manager().shutdown();
                            return;
                        }
                        poolLock.unlock();
                    }
                }
                try { Thread.sleep(10); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            if (!poolLock.isHeldByCurrentThread()) {
                poolLock.lock();
            }
        }
        throw new ProviderPoolExhaustedException(poolConfig.maxProcesses());
    }

    private void evictIdle() {
        long idleThresholdNanos = poolConfig.idleTimeout().toNanos();
        poolLock.lock();
        try {
            var it = pool.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next().getValue();
                if (entry.refCount() == 0 && entry.idleSinceNanos() > idleThresholdNanos) {
                    it.remove();
                    entry.manager().shutdown();
                }
            }
        } finally {
            poolLock.unlock();
        }
    }

    private ProviderProcessManager getSchemaOnlyManager() {
        if (schemaOnlyManager == null) {
            synchronized (schemaLock) {
                if (schemaOnlyManager == null) {
                    schemaOnlyManager = factory.create();
                }
            }
        }
        return schemaOnlyManager;
    }

    @FunctionalInterface
    public interface ProcessManagerFactory {
        ProviderProcessManager create();
    }
}
