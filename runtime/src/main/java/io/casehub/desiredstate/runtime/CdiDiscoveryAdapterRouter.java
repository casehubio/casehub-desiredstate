package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DiscoveryAdapter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class CdiDiscoveryAdapterRouter extends DefaultDiscoveryAdapterRouter {

    @Inject
    public CdiDiscoveryAdapterRouter(Instance<DiscoveryAdapter> adapters) {
        super(adapters.stream().toList());
    }

    CdiDiscoveryAdapterRouter() {
        super();
    }
}
