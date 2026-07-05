package io.casehub.ras.runtime;

import io.casehub.ras.api.Ganglion;
import io.casehub.ras.api.SituationDefinitionProvider;

import java.util.List;

/**
 * Public test helper for constructing SituationDefinitionRegistry
 * in unit tests outside the ras runtime package.
 */
public class TestSituationDefinitionRegistry extends SituationDefinitionRegistry {

    public TestSituationDefinitionRegistry(List<SituationDefinitionProvider> providers,
                                           List<Ganglion> ganglia) {
        super(providers, ganglia);
    }

    public static SituationDefinitionRegistry create(List<SituationDefinitionProvider> providers,
                                                     List<Ganglion> ganglia) {
        return new TestSituationDefinitionRegistry(providers, ganglia);
    }
}
