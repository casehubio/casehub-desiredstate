package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.SituationRecompiler;
import io.casehub.ras.api.ActiveSituation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class SituationRecompilerEngine {

    private static final Logger LOG = Logger.getLogger(SituationRecompilerEngine.class.getName());

    @FunctionalInterface
    public interface DrainHandler {
        void drain(String tenancyId, List<QueuedSituation> queued);
    }

    public sealed interface QueuedSituation {
        String tenancyId();

        record Recompile(String tenancyId, ActiveSituation situation,
                         DesiredStateGraphFactory factory) implements QueuedSituation {}

        record Resolved(String tenancyId, String situationId,
                        DesiredStateGraphFactory factory) implements QueuedSituation {}
    }

    static final class StabilizationState {
        final List<QueuedSituation> queue = new ArrayList<>();
        ScheduledFuture<?> drainFuture;
    }

    private final    List<SituationRecompiler>                     recompilers;
    private final    Duration                                      stabilizationWindow;
    private final    ScheduledExecutorService                      scheduler;
    private volatile DrainHandler                                  drainHandler;
    private final    ConcurrentHashMap<String, StabilizationState> stabilization = new ConcurrentHashMap<>();

    public SituationRecompilerEngine(List<SituationRecompiler> recompilers) {
        this(recompilers, Duration.ZERO, null, null);
    }

    public SituationRecompilerEngine(
            List<SituationRecompiler> recompilers,
            Duration stabilizationWindow,
            ScheduledExecutorService scheduler,
            DrainHandler drainHandler) {
        this.recompilers         = recompilers.stream()
                                              .sorted(Comparator.comparingInt(SituationRecompiler::priority))
                                              .toList();
        this.stabilizationWindow = stabilizationWindow != null ? stabilizationWindow : Duration.ZERO;
        this.scheduler           = scheduler;
        this.drainHandler        = drainHandler;
    }

    public Optional<CompilationResult> recompile(
            String tenancyId,
            DesiredStateGraph current, ActualState actual,
            ActiveSituation situation, DesiredStateGraphFactory factory) {
        if (isStabilizationEnabled()) {
            StabilizationState state = stabilization.get(tenancyId);
            if (state != null) {
                synchronized (state) {
                    state.queue.add(new QueuedSituation.Recompile(tenancyId, situation, factory));
                }
                LOG.fine(() -> "Situation " + situation.situationId()
                               + " queued during stabilization for tenant " + tenancyId);
                return Optional.empty();
            }
        }

        for (SituationRecompiler recompiler : recompilers) {
            Optional<CompilationResult> result = recompiler.recompile(
                    tenancyId, current, actual, situation, factory);
            if (result.isPresent()) {
                startStabilizationWindow(tenancyId);
                return result;
            }
        }
        return Optional.empty();
    }

    public Optional<CompilationResult> recompileImmediate(
            String tenancyId,
            DesiredStateGraph current, ActualState actual,
            ActiveSituation situation, DesiredStateGraphFactory factory) {
        for (SituationRecompiler recompiler : recompilers) {
            Optional<CompilationResult> result = recompiler.recompile(
                    tenancyId, current, actual, situation, factory);
            if (result.isPresent()) {return result;}
        }
        return Optional.empty();
    }


    public Optional<CompilationResult> situationResolved(
            String tenancyId, String situationId,
            DesiredStateGraph current, ActualState actual,
            DesiredStateGraphFactory factory) {
        if (isStabilizationEnabled()) {
            StabilizationState state = stabilization.get(tenancyId);
            if (state != null) {
                synchronized (state) {
                    state.queue.add(new QueuedSituation.Resolved(tenancyId, situationId, factory));
                }
                LOG.fine(() -> "Situation resolved " + situationId
                               + " queued during stabilization for tenant " + tenancyId);
                return Optional.empty();
            }
        }

        for (SituationRecompiler recompiler : recompilers) {
            Optional<CompilationResult> result = recompiler.situationResolved(
                    tenancyId, situationId, current, actual, factory);
            if (result.isPresent()) {
                startStabilizationWindow(tenancyId);
                return result;
            }
        }
        return Optional.empty();
    }

    public void setDrainHandler(DrainHandler handler) {
        this.drainHandler = handler;
    }

    public void clearStabilization(String tenancyId) {
        StabilizationState removed = stabilization.remove(tenancyId);
        if (removed != null) {
            synchronized (removed) {
                if (removed.drainFuture != null) {
                    removed.drainFuture.cancel(false);
                }
            }
        }
    }

    public void shutdown() {
        stabilization.keySet().forEach(this::clearStabilization);
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private boolean isStabilizationEnabled() {
        return !stabilizationWindow.isZero() && !stabilizationWindow.isNegative();
    }

    private void startStabilizationWindow(String tenancyId) {
        if (!isStabilizationEnabled() || scheduler == null) {
            return;
        }
        StabilizationState state = new StabilizationState();
        synchronized (state) {
            StabilizationState previous = stabilization.put(tenancyId, state);
            if (previous != null) {
                synchronized (previous) {
                    if (previous.drainFuture != null) {
                        previous.drainFuture.cancel(false);
                    }
                    state.queue.addAll(previous.queue);
                }
            }
            state.drainFuture = scheduler.schedule(
                    () -> drainWindow(tenancyId),
                    stabilizationWindow.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void drainWindow(String tenancyId) {
        StabilizationState state = stabilization.remove(tenancyId);
        if (state == null) {
            return;
        }
        List<QueuedSituation> queued;
        synchronized (state) {
            queued = new ArrayList<>(state.queue);
            state.queue.clear();
        }
        if (!queued.isEmpty() && drainHandler != null) {
            LOG.info("Draining " + queued.size() + " queued situation(s) for tenant " + tenancyId);
            drainHandler.drain(tenancyId, queued);
        }
    }
}
