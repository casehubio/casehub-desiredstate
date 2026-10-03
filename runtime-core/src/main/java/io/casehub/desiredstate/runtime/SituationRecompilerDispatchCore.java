package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualStateAdapterRouter;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationSource;

import java.util.Optional;
import java.util.logging.Logger;

public class SituationRecompilerDispatchCore {

    private static final Logger LOG = Logger.getLogger(SituationRecompilerDispatchCore.class.getName());

    private final SituationRecompilerEngine engine;
    private final LifecycleManager lifecycleManager;
    private final ReconciliationLoop reconciliationLoop;
    private final ActualStateAdapterRouter actualStateRouter;
    private final DesiredStateGraphFactory graphFactory;
    private final SituationSource situationSource;

    public SituationRecompilerDispatchCore(
            SituationRecompilerEngine engine,
            LifecycleManager lifecycleManager,
            ReconciliationLoop reconciliationLoop,
            ActualStateAdapterRouter actualStateRouter,
            DesiredStateGraphFactory graphFactory,
            SituationSource situationSource) {
        this.engine = engine;
        this.lifecycleManager = lifecycleManager;
        this.reconciliationLoop = reconciliationLoop;
        this.actualStateRouter = actualStateRouter;
        this.graphFactory = graphFactory;
        this.situationSource = situationSource;
    }

    public void handleColdStart() {
        for (String tenancyId : reconciliationLoop.tenantIds()) {
            var activeSituations = situationSource.activeSituations(tenancyId);
            if (activeSituations.isEmpty()) {
                continue;
            }
            DesiredStateGraph current = reconciliationLoop.getDesired(tenancyId);
            var actual = actualStateRouter.readActual(current, tenancyId);
            for (ActiveSituation situation : activeSituations) {
                var result = engine.recompile(tenancyId, current, actual, situation, graphFactory);
                if (result.isPresent()) {
                    applyResult(tenancyId, result);
                    current = reconciliationLoop.getDesired(tenancyId);
                    actual = actualStateRouter.readActual(current, tenancyId);
                }
            }
            LOG.info("Cold-start recovery: processed " + activeSituations.size()
                    + " active situation(s) for tenant " + tenancyId);
        }
    }

    public void handleSituationChange(SituationChangeEvent event) {
        String tenancyId = event.tenancyId();
        if (!reconciliationLoop.tenantIds().contains(tenancyId)) {
            return;
        }
        DesiredStateGraph currentGraph = reconciliationLoop.getDesired(tenancyId);
        var actual = actualStateRouter.readActual(currentGraph, tenancyId);

        switch (event.changeType()) {
            case TRIGGERED -> {
                ActiveSituation situation = toActiveSituation(event);
                Optional<CompilationResult> result = engine.recompile(
                        tenancyId, currentGraph, actual, situation, graphFactory);
                applyResult(tenancyId, result);
            }
            case RESOLVED -> {
                Optional<CompilationResult> result = engine.situationResolved(
                        tenancyId, event.situationId(), currentGraph, actual, graphFactory);
                applyResult(tenancyId, result);
            }
            default -> { }
        }
    }

    private void applyResult(String tenancyId, Optional<CompilationResult> result) {
        if (result.isEmpty()) {
            return;
        }
        lifecycleManager.updateDesired(tenancyId, result.get());
        reconciliationLoop.requestReconciliation(tenancyId);
        LOG.fine(() -> "Situation recompilation applied for tenant " + tenancyId);
    }

    static ActiveSituation toActiveSituation(SituationChangeEvent event) {
        SituationContext ctx = event.context();
        double confidence = ctx.detections().isEmpty() ? 0.5
                : ctx.detections().getLast().result().confidence();
        return new ActiveSituation(
                event.situationId(),
                event.correlationKey(),
                event.tenancyId(),
                confidence,
                event.metadata(),
                ctx.firstSignal(),
                ctx.lastSignal(),
                ctx.triggerCount());
    }
}
