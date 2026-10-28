package io.casehub.desiredstate.bridge.terraform;

import io.casehub.platform.api.credentials.CredentialResolver;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class TenantProviderRegistry implements AutoCloseable {

    private final ConcurrentHashMap<String, ProviderProcessManager> managers = new ConcurrentHashMap<>();
    private final ProcessManagerFactory factory;
    private final CredentialResolver credentialResolver;
    private final String credentialRef;
    private volatile ProviderProcessManager schemaOnlyManager;
    private final Object schemaLock = new Object();

    public TenantProviderRegistry(ProcessManagerFactory factory,
                                   CredentialResolver credentialResolver,
                                   String credentialRef) {
        this.factory = factory;
        this.credentialResolver = credentialResolver;
        this.credentialRef = credentialRef;
    }

    public TerraformProtocol getProtocol(String tenancyId) {
        return managers.computeIfAbsent(tenancyId, this::createAndConfigure)
                       .getProtocol();
    }

    public List<DiscoveredType> discoverTypes() {
        return getSchemaOnlyManager().discoverTypes();
    }

    public void evict(String tenancyId) {
        var manager = managers.remove(tenancyId);
        if (manager != null) {
            manager.shutdown();
        }
    }

    @Override
    public void close() {
        managers.values().forEach(ProviderProcessManager::shutdown);
        managers.clear();
        synchronized (schemaLock) {
            if (schemaOnlyManager != null) {
                schemaOnlyManager.shutdown();
                schemaOnlyManager = null;
            }
        }
    }

    private ProviderProcessManager createAndConfigure(String tenancyId) {
        var manager = factory.create();
        var protocol = manager.getProtocol();
        if (credentialRef != null && credentialResolver != null) {
            var credentials = credentialResolver.resolve(credentialRef);
            if (!credentials.isEmpty()) {
                protocol.configure(new HashMap<>(credentials));
            }
        }
        return manager;
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
