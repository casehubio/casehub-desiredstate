package io.casehub.desiredstate.runtime;

import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.SituationSource;

import java.util.List;

public class NoOpSituationSource implements SituationSource {
    @Override
    public List<ActiveSituation> activeSituations(String tenancyId) {
        return List.of();
    }
}
