package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.SituationRecompiler;
import io.casehub.ras.api.ActiveSituation;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * Default no-op implementation of {@link SituationRecompiler}.
 * Always returns {@link Optional#empty()}, signaling that no replan is needed.
 *
 * <p>Domains that want situation-driven replanning should provide a real
 * implementation via CDI, which will override this default bean.
 */
@DefaultBean
@ApplicationScoped
public class NoOpSituationRecompiler implements SituationRecompiler {

    @Override
    public Optional<DesiredStateGraph> recompile(
            DesiredStateGraph current,
            ActiveSituation situation,
            DesiredStateGraphFactory factory) {
        return Optional.empty();
    }
}
