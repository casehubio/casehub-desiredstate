package io.casehub.desiredstate.runtime;

import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.SituationSource;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@DefaultBean
@ApplicationScoped
public class NoOpSituationSource implements SituationSource {
    @Override
    public List<ActiveSituation> activeSituations(String tenancyId) {
        return List.of();
    }
}
