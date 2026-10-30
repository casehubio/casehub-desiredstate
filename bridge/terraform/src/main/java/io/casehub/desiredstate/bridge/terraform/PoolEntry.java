package io.casehub.desiredstate.bridge.terraform;

import java.util.concurrent.atomic.AtomicInteger;

final class PoolEntry {

    private final ProviderProcessManager manager;
    private final String configHash;
    private final AtomicInteger refCount = new AtomicInteger(0);
    private volatile long lastAccessNanos = System.nanoTime();

    PoolEntry(ProviderProcessManager manager, String configHash) {
        this.manager = manager;
        this.configHash = configHash;
    }

    ProviderProcessManager manager() { return manager; }
    String configHash() { return configHash; }
    int refCount() { return refCount.get(); }
    long lastAccessNanos() { return lastAccessNanos; }

    int incrementRef() { return refCount.incrementAndGet(); }
    int decrementRef() { return refCount.decrementAndGet(); }

    void touch() { lastAccessNanos = System.nanoTime(); }

    long idleSinceNanos() { return System.nanoTime() - lastAccessNanos; }
}
