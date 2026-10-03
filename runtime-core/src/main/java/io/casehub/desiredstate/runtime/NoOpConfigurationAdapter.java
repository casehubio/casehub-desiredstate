package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.AdaptedConfiguration;
import io.casehub.desiredstate.api.ConfigurationAdapter;
import io.casehub.desiredstate.api.RetrievalContext;
import io.casehub.desiredstate.api.RetrievedConfiguration;

import java.util.Optional;

public class NoOpConfigurationAdapter implements ConfigurationAdapter {
    @Override
    public Optional<AdaptedConfiguration> adapt(RetrievedConfiguration retrieved, RetrievalContext context) {
        return Optional.empty();
    }
}
