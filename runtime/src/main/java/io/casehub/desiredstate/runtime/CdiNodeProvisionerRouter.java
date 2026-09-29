package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.stream.StreamSupport;

@DefaultBean
@ApplicationScoped
public class CdiNodeProvisionerRouter extends DefaultNodeProvisionerRouter {


    protected CdiNodeProvisionerRouter() {
    }

    @Inject
    public CdiNodeProvisionerRouter(Instance<NodeProvisioner> provisioners,
                                     PreferenceProvider preferenceProvider) {
        super(StreamSupport.stream(provisioners.spliterator(), false).toList(),
              preferenceProvider);
    }
}
