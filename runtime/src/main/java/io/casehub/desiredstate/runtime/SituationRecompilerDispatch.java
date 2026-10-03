package io.casehub.desiredstate.runtime;

import io.casehub.ras.api.SituationChangeEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

@ApplicationScoped
public class SituationRecompilerDispatch {

    @Inject
    SituationRecompilerDispatchCore core;

    void onColdStart(@Observes StartupEvent ev) {
        core.handleColdStart();
    }

    void onSituationChange(@ObservesAsync SituationChangeEvent event) {
        core.handleSituationChange(event);
    }
}
