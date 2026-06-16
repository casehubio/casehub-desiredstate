package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-tenant event-driven reconciliation loop.
 *
 * <p>Each tenant gets its own internal {@link TenantLoop} instance stored in a
 * {@link ConcurrentHashMap}. Two trigger paths feed into the same reconcile cycle:
 *
 * <ol>
 *   <li><b>Event-driven:</b> subscribes to {@link EventSource#stream()} and debounces
 *       events within a configurable window, batching rapid-fire events into a single
 *       reconciliation cycle.</li>
 *   <li><b>Periodic re-sync:</b> a scheduled task at a configurable interval
 *       (default 5 minutes) triggers a full reconciliation.</li>
 * </ol>
 *
 * <p>{@link #start(String, DesiredStateGraph)} fires an immediate initial reconciliation.
 * {@link #updateDesired(String, DesiredStateGraph)} atomically swaps the desired graph;
 * in-flight execution completes against the old version while the next cycle picks up the new one.
 *
 * <p>Fault feedback: after execution, any {@link StepOutcome.Failed} outcomes create
 * {@link FaultEvent}s evaluated through {@link FaultPolicyEngine}. Resulting mutations
 * are applied to the desired graph, and the next cycle will incorporate the changes.
 *
 * <p>The reconciliation loop never dies on exception. A dead loop is worse than a failed cycle.
 */
@ApplicationScoped
public class ReconciliationLoop {

    private static final Logger LOG = Logger.getLogger(ReconciliationLoop.class.getName());

    static final Duration DEFAULT_DEBOUNCE = Duration.ofSeconds(1);
    static final Duration DEFAULT_RESYNC = Duration.ofMinutes(5);

    private final TransitionPlanner planner;
    private final TransitionExecutor executor;
    private final ActualStateAdapter actualStateAdapter;
    private final FaultPolicyEngine faultPolicyEngine;
    private final EventSource eventSource;
    private final Duration debounceWindow;
    private final Duration resyncInterval;

    private final ConcurrentHashMap<String, TenantLoop> loops = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    /**
     * CDI constructor with default timers.
     */
    public ReconciliationLoop(
            TransitionPlanner planner,
            TransitionExecutor executor,
            ActualStateAdapter actualStateAdapter,
            FaultPolicyEngine faultPolicyEngine,
            EventSource eventSource) {
        this(planner, executor, actualStateAdapter, faultPolicyEngine, eventSource,
             DEFAULT_DEBOUNCE, DEFAULT_RESYNC);
    }

    /**
     * Test-friendly constructor accepting configurable durations.
     */
    public ReconciliationLoop(
            TransitionPlanner planner,
            TransitionExecutor executor,
            ActualStateAdapter actualStateAdapter,
            FaultPolicyEngine faultPolicyEngine,
            EventSource eventSource,
            Duration debounceWindow,
            Duration resyncInterval) {
        this.planner = planner;
        this.executor = executor;
        this.actualStateAdapter = actualStateAdapter;
        this.faultPolicyEngine = faultPolicyEngine;
        this.eventSource = eventSource;
        this.debounceWindow = debounceWindow;
        this.resyncInterval = resyncInterval;
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "reconciliation-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts a reconciliation loop for the given tenant. Triggers an immediate initial
     * reconciliation, then subscribes to events and schedules periodic re-sync.
     *
     * @param tenancyId the tenant identifier
     * @param desired   the initial desired state graph
     * @throws IllegalStateException if a loop is already running for this tenant
     */
    public void start(String tenancyId, DesiredStateGraph desired) {
        TenantLoop loop = new TenantLoop(tenancyId, desired);
        TenantLoop existing = loops.putIfAbsent(tenancyId, loop);
        if (existing != null) {
            throw new IllegalStateException("Reconciliation loop already running for tenant: " + tenancyId);
        }
        loop.start();
    }

    /**
     * Stops the reconciliation loop for the given tenant.
     *
     * @param tenancyId the tenant identifier
     */
    public void stop(String tenancyId) {
        TenantLoop loop = loops.remove(tenancyId);
        if (loop != null) {
            loop.stop();
        }
    }

    /**
     * Atomically swaps the desired graph for a tenant. In-flight execution completes
     * against the previous version; the new graph takes effect on the next reconciliation cycle.
     *
     * @param tenancyId  the tenant identifier
     * @param newDesired the new desired state graph
     * @throws IllegalStateException if no loop is running for this tenant
     */
    public void updateDesired(String tenancyId, DesiredStateGraph newDesired) {
        TenantLoop loop = loops.get(tenancyId);
        if (loop == null) {
            throw new IllegalStateException("No reconciliation loop running for tenant: " + tenancyId);
        }
        loop.updateDesired(newDesired);
    }

    @PreDestroy
    void shutdown() {
        for (String tenancyId : loops.keySet()) {
            stop(tenancyId);
        }
        scheduler.shutdownNow();
    }

    /**
     * Returns the number of active tenant loops. Visible for testing.
     */
    int activeTenantCount() {
        return loops.size();
    }

    /**
     * Internal per-tenant reconciliation loop.
     */
    private class TenantLoop {

        private final String tenancyId;
        private final AtomicReference<DesiredStateGraph> desiredRef;
        private volatile Cancellable eventSubscription;
        private volatile ScheduledFuture<?> resyncFuture;

        TenantLoop(String tenancyId, DesiredStateGraph desired) {
            this.tenancyId = tenancyId;
            this.desiredRef = new AtomicReference<>(desired);
        }

        void start() {
            // Immediate initial reconciliation
            reconcile();

            // Event-driven: subscribe with debounce
            eventSubscription = eventSource.stream()
                .group().intoLists().every(debounceWindow)
                .filter(batch -> !batch.isEmpty())
                .subscribe().with(
                    batch -> reconcile(),
                    failure -> LOG.log(Level.WARNING,
                        "Event stream error for tenant " + tenancyId, failure)
                );

            // Periodic re-sync
            long resyncMillis = resyncInterval.toMillis();
            resyncFuture = scheduler.scheduleAtFixedRate(
                this::reconcile,
                resyncMillis, resyncMillis, TimeUnit.MILLISECONDS);
        }

        void stop() {
            if (eventSubscription != null) {
                eventSubscription.cancel();
            }
            if (resyncFuture != null) {
                resyncFuture.cancel(false);
            }
        }

        void updateDesired(DesiredStateGraph newDesired) {
            desiredRef.set(newDesired);
        }

        /**
         * Single reconciliation cycle. Never throws — catches and logs all exceptions.
         */
        private void reconcile() {
            try {
                DesiredStateGraph desired = desiredRef.get();

                // 1. Read actual state
                ActualState actual = actualStateAdapter.readActual(desired);

                // 2. Detect DRIFTED nodes → NODE_DEGRADED fault events
                DesiredStateGraph mutated = desired;
                for (Map.Entry<NodeId, DesiredNode> entry : desired.nodes().entrySet()) {
                    NodeStatus status = actual.statuses().getOrDefault(entry.getKey(), NodeStatus.UNKNOWN);
                    if (status == NodeStatus.DRIFTED) {
                        FaultEvent faultEvent = new FaultEvent(
                            entry.getKey(), FaultType.NODE_DEGRADED, "Node drifted from desired spec");
                        List<GraphMutation> mutations = faultPolicyEngine.evaluate(faultEvent, mutated);
                        for (GraphMutation mutation : mutations) {
                            mutated = mutated.withMutation(mutation);
                        }
                    }
                }
                if (mutated != desired) {
                    desiredRef.compareAndSet(desired, mutated);
                    desired = mutated;
                }

                // 3. Plan transition
                TransitionPlan plan = planner.plan(desired, actual);
                if (plan.isEmpty()) {
                    return;
                }

                // 4. Execute transition
                TransitionResult result = executor.execute(plan).await().indefinitely();

                // 5. Fault feedback — accumulate all mutations, single CAS
                // Build removal node set for fault type determination
                Set<NodeId> removalNodeIds = new HashSet<>();
                for (OrderedStep step : plan.removals()) {
                    removalNodeIds.add(step.node().id());
                }

                mutated = desired;
                for (Map.Entry<NodeId, StepOutcome> entry : result.outcomes().entrySet()) {
                    if (entry.getValue() instanceof StepOutcome.Failed failed) {
                        FaultType faultType = removalNodeIds.contains(entry.getKey())
                            ? FaultType.DEPROVISION_FAILED
                            : FaultType.PROVISION_FAILED;
                        FaultEvent faultEvent = new FaultEvent(
                            entry.getKey(), faultType, failed.reason());
                        List<GraphMutation> mutations = faultPolicyEngine.evaluate(faultEvent, mutated);
                        for (GraphMutation mutation : mutations) {
                            mutated = mutated.withMutation(mutation);
                        }
                    }
                }
                if (mutated != desired) {
                    desiredRef.compareAndSet(desired, mutated);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING,
                    "Reconciliation cycle failed for tenant " + tenancyId, e);
            }
        }
    }
}
