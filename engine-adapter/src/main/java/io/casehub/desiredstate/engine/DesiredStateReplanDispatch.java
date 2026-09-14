package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapterRouter;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.SituationRecompiler;
import io.casehub.desiredstate.runtime.LifecycleManager;
import io.casehub.desiredstate.runtime.ReconciliationLoop;
import io.casehub.desiredstate.runtime.SituationRecompilerEngine;
import io.casehub.engine.flow.CallableDispatchRegistry;
import io.casehub.ras.api.ActiveSituation;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Dispatch handler for RAS-triggered desired-state replan.
 *
 * <p>Registers {@code desiredstate:replan} via {@link CallableDispatchRegistry}.
 * When called from a workflow step, it:
 * <ol>
 *   <li>Extracts {@code tenancyId} and situation details from input data</li>
 *   <li>Reads current desired graph via {@link ReconciliationLoop#getDesired(String)}</li>
 *   <li>Builds {@link ActiveSituation} from input</li>
 *   <li>Reads actual state via {@link ActualStateAdapterRouter}</li>
 *   <li>Calls {@link SituationRecompiler#recompile(DesiredStateGraph, ActualState, ActiveSituation, DesiredStateGraphFactory)}</li>
 *   <li>If recompiler returns a new result, calls {@link LifecycleManager#updateDesired(String, CompilationResult)}</li>
 * </ol>
 *
 * <p>The recompiler may return {@code Optional.empty()}, signaling no replan needed.
 * In that case, the current graph remains unchanged.
 */
@ApplicationScoped
public class DesiredStateReplanDispatch {

    private final LifecycleManager lifecycleManager;
    private final ReconciliationLoop reconciliationLoop;
    private final SituationRecompilerEngine recompilerEngine;
    private final DesiredStateGraphFactory graphFactory;
    private final CallableDispatchRegistry callRegistry;
    private final ActualStateAdapterRouter actualStateRouter;
    private final jakarta.enterprise.inject.Instance<io.casehub.desiredstate.runtime.composition.CrossDomainCompositionEngine> compositionEngine;

    @Inject
    public DesiredStateReplanDispatch(
            LifecycleManager lifecycleManager,
            ReconciliationLoop reconciliationLoop,
            SituationRecompilerEngine recompilerEngine,
            DesiredStateGraphFactory graphFactory,
            CallableDispatchRegistry callRegistry,
            ActualStateAdapterRouter actualStateRouter,
            jakarta.enterprise.inject.Instance<io.casehub.desiredstate.runtime.composition.CrossDomainCompositionEngine> compositionEngine) {
        this.lifecycleManager = lifecycleManager;
        this.reconciliationLoop = reconciliationLoop;
        this.recompilerEngine = recompilerEngine;
        this.graphFactory = graphFactory;
        this.callRegistry = callRegistry;
        this.actualStateRouter = actualStateRouter;
        this.compositionEngine = compositionEngine;
    }

    DesiredStateReplanDispatch(
            LifecycleManager lifecycleManager,
            ReconciliationLoop reconciliationLoop,
            SituationRecompilerEngine recompilerEngine,
            DesiredStateGraphFactory graphFactory,
            CallableDispatchRegistry callRegistry,
            ActualStateAdapterRouter actualStateRouter) {
        this(lifecycleManager, reconciliationLoop, recompilerEngine, graphFactory,
                callRegistry, actualStateRouter, null);
    }

    void register() {
        callRegistry.register("desiredstate:replan", this::replan);
    }

    @PostConstruct
    void init() {
        register();
    }

    CompletableFuture<Map<String, Object>> replan(
            String workflowInstanceId, Map<String, Object> args) {
        try {
            String tenancyId      = requireString(args, "tenancyId");
            String situationId    = requireString(args, "situationId");
            String correlationKey = requireString(args, "correlationKey");
            double confidence     = requireDouble(args, "confidence");
            Object evidenceObj    = args.getOrDefault("evidence", Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> evidence = (evidenceObj instanceof Map)
                                           ? (Map<String, Object>) evidenceObj
                                           : Map.of();
            Instant since        = Instant.parse(requireString(args, "since"));
            Instant lastSignal   = Instant.parse(requireString(args, "lastSignal"));
            int     triggerCount = requireInt(args, "triggerCount");

            ActiveSituation situation = new ActiveSituation(
                    situationId, correlationKey, tenancyId, confidence,
                    evidence, since, lastSignal, triggerCount);

            DesiredStateGraph current = reconciliationLoop.getDesired(tenancyId);
            ActualState       actual  = actualStateRouter.readActual(current, tenancyId);

            Optional<CompilationResult> newResult;
            if (compositionEngine != null && compositionEngine.isResolvable() && compositionEngine.get().isActive()) {
                newResult = compositionEngine.get().handleReplan(
                        tenancyId, actual, situation, graphFactory);
            } else {
                newResult = recompilerEngine.recompile(
                        tenancyId, current, actual, situation, graphFactory);
                newResult.ifPresent(r -> lifecycleManager.updateDesired(tenancyId, r));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("situationId", situationId);

            if (newResult.isPresent()) {
                result.put("status", "REPLANNED");
            } else {
                result.put("status", "NO_CHANGE");
            }

            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static String requireString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required arg: " + key);
        }
        return value.toString();
    }

    private static double requireDouble(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required arg: " + key);
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    private static int requireInt(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required arg: " + key);
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(value.toString());
    }
}
