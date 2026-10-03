package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ConfigurationRetriever;
import io.casehub.desiredstate.api.RetrievalContext;
import io.casehub.desiredstate.api.RetrievedConfiguration;

import java.util.List;

public class NoOpConfigurationRetriever implements ConfigurationRetriever {
    @Override
    public List<RetrievedConfiguration> retrieve(RetrievalContext context, int maxResults) {
        return List.of();
    }
}
