package io.casehub.desiredstate.runtime.spring;

import io.casehub.desiredstate.runtime.SituationRecompilerDispatchCore;
import io.casehub.ras.api.SituationChangeEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

public class SpringSituationRecompilerDispatch {

    private final SituationRecompilerDispatchCore core;

    public SpringSituationRecompilerDispatch(SituationRecompilerDispatchCore core) {
        this.core = core;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onColdStart() {
        core.handleColdStart();
    }

    @EventListener
    public void onSituationChange(SituationChangeEvent event) {
        core.handleSituationChange(event);
    }
}