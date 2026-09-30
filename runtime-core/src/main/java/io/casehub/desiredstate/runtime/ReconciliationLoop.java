package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapterRouter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GlobalReconciliationListener;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.InMemoryReconciliationStateStore;
import io.casehub.desiredstate.api.MergedEventSource;
import io.casehub.desiredstate.api.NodeAlreadyConvergedData;
import io.casehub.desiredstate.api.NodeDriftedData;
import io.casehub.desiredstate.api.NodeFaultedData;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeProvisionerRouter;
import io.casehub.desiredstate.api.NodeRecoveredData;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.ReconciliationCompletedData;
import io.casehub.desiredstate.api.ReconciliationListener;
import io.casehub.desiredstate.api.ReconciliationStateStore;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TargetStatus;
import io.casehub.desiredstate.api.TransitionExecutor;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.api.TransitionResult;
import io.casehub.neocortex.memory.cbr.CbrOutcomeData;
import io.cloudevents.CloudEvent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.smallrye.mutiny.subscription.Cancellable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Per-tenant event-driven reconciliation loop.
 *
 * <p>Each tenant gets its own internal {@link TenantLoop} instance stored in a
 * {@link ConcurrentHashMap}. Two trigger paths feed into the same reconcile cycle:
 *
 * <ol>
 *   <li><b>Event-driven:</b> subscribes to {@link MergedEventSource#stream()} and debounces
 *       events within a configurable window, batching rapid-fire events into a single
 *       full-graph reconciliation cycle.</li>
 *   <li><b>Periodic re-sync:</b> interval-grouped timers derived from
 *       {@link NodeProvisionerRouter#resyncIntervalFor(NodeType)}. Types sharing the same
 *       effective interval are grouped into a single {@link ScheduledFuture} that fires
 *       {@link TenantLoop#reconcileTypes(Set)} with the filtered graph.</li>
 * </ol>
 *
 * <p>{@link #start(String, DesiredStateGraph)} fires an immediate initial full-graph
 * reconciliation. {@link #updateDesired(String, DesiredStateGraph)} atomically swaps the
 * desired graph and recomputes interval groups if node types changed; in-flight execution
 * completes against the old version while the next cycle picks up the new one.
 *
 * <p>Fault feedback: after execution, any {@link StepOutcome.Failed} outcomes create
 * {@link FaultEvent}s evaluated through {@link FaultPolicyEngine}. Resulting mutations
 * are applied to the desired graph via a CAS merge-and-retry loop, ensuring concurrent
 * mutations are never silently dropped.
 *
 * <p>The reconciliation loop never dies on exception. A dead loop is worse than a failed cycle.
 */
public class ReconciliationLoop {

    public static final Duration DEFAULT_DEBOUNCE = Duration.ofSeconds(1);
    public static final Duration DEFAULT_RESYNC = Duration.ofMinutes(5);
    private static final Logger LOG = Logger.getLogger(ReconciliationLoop.class.getName());
    private final TransitionPlanner planner;
    private final TransitionExecutor executor;
    private final ActualStateAdapterRouter actualStateAdapterRouter;
    private final FaultPolicyEngine faultPolicyEngine;
    private final MergedEventSource mergedEventSource;
    private final NodeProvisionerRouter router;
    private final Duration debounceWindow;
    private final Duration resyncOverride;
    private final Consumer<CloudEvent> cloudEventSink;
    private final ReconciliationEventEmitter eventEmitter;
    private final CbrProposalTracker cbrTracker;
    private final List<GlobalReconciliationListener> globalListeners;
    private final ReconciliationStateStore reconciliationStateStore;
    private final int                      nodeOutcomesThreshold;


    private final ConcurrentHashMap<String, TenantLoop> loops = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    protected ReconciliationLoop(
            TransitionPlanner planner,
            TransitionExecutor executor,
            ActualStateAdapterRouter actualStateAdapterRouter,
            FaultPolicyEngine faultPolicyEngine,
            MergedEventSource mergedEventSource) {
        this(planner, executor, actualStateAdapterRouter, faultPolicyEngine, mergedEventSource,
             null, DEFAULT_DEBOUNCE, DEFAULT_RESYNC, null, null, List.of(), null,
             ReconciliationCompletedData.NODE_OUTCOMES_THRESHOLD);
    }

    public ReconciliationLoop(
            TransitionPlanner planner,
            TransitionExecutor executor,
            ActualStateAdapterRouter actualStateAdapterRouter,
            FaultPolicyEngine faultPolicyEngine,
            MergedEventSource mergedEventSource,
            NodeProvisionerRouter router,
            Duration debounceWindow,
            Duration resyncOverride,
            Consumer<CloudEvent> cloudEventSink,
            CbrProposalTracker cbrTracker,
            List<GlobalReconciliationListener> globalListeners,
            ReconciliationStateStore reconciliationStateStore,
            int nodeOutcomesThreshold) {
        this.planner                  = planner;
        this.executor                 = executor;
        this.actualStateAdapterRouter = actualStateAdapterRouter;
        this.faultPolicyEngine        = faultPolicyEngine;
        this.mergedEventSource        = mergedEventSource;
        this.router                   = router;
        this.debounceWindow           = debounceWindow;
        this.resyncOverride           = resyncOverride;
        this.cloudEventSink           = cloudEventSink != null ? cloudEventSink : event -> {};
        this.eventEmitter             = new ReconciliationEventEmitter();
        this.cbrTracker               = cbrTracker != null ? cbrTracker : new CbrProposalTracker();
        this.globalListeners          = globalListeners != null ? List.copyOf(globalListeners) : List.of();
        this.reconciliationStateStore = reconciliationStateStore != null ? reconciliationStateStore : new InMemoryReconciliationStateStore();
        this.nodeOutcomesThreshold    = nodeOutcomesThreshold;

        int poolSize = computeSchedulerPoolSize();
        this.scheduler = Executors.newScheduledThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "reconciliation-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public static Builder builder(TransitionPlanner planner,
                                  TransitionExecutor executor,
                                  ActualStateAdapterRouter actualStateAdapterRouter,
                                  FaultPolicyEngine faultPolicyEngine,
                                  MergedEventSource mergedEventSource) {
        return new Builder(planner, executor, actualStateAdapterRouter, faultPolicyEngine, mergedEventSource);
    }

    private int computeSchedulerPoolSize() {
        if (resyncOverride != null || router == null) {
            return 1;
        }
        // Count distinct interval groups from the router
        Set<Duration> distinctIntervals = new HashSet<>();
        for (NodeType type : router.allHandledTypes()) {
            distinctIntervals.add(router.resyncIntervalFor(type));
        }
        int groups = Math.max(1, distinctIntervals.size());
        return Math.min(groups, Runtime.getRuntime().availableProcessors());
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
     * Starts a reconciliation loop for the given tenant with a lifecycle listener.
     * The listener fires on every reconciliation cycle, including empty-plan cycles.
     *
     * @param tenancyId the tenant identifier
     * @param desired   the initial desired state graph
     * @param listener  listener notified after each reconciliation cycle
     * @throws IllegalStateException if a loop is already running for this tenant
     */
    public void start(String tenancyId, DesiredStateGraph desired, ReconciliationListener listener) {
        TenantLoop loop = new TenantLoop(tenancyId, desired, listener);
        TenantLoop existing = loops.putIfAbsent(tenancyId, loop);
        if (existing != null) {
            throw new IllegalStateException("Reconciliation loop already running for tenant: " + tenancyId);
        }
        loop.start();
    }

    /**
     * Sets or replaces the reconciliation listener for a running tenant loop.
     *
     * @param tenancyId the tenant identifier
     * @param listener  the new listener (may be null to clear)
     * @throws IllegalStateException if no loop is running for this tenant
     */
    public void setListener(String tenancyId, ReconciliationListener listener) {
        TenantLoop loop = loops.get(tenancyId);
        if (loop == null) {
            throw new IllegalStateException("No reconciliation loop running for tenant: " + tenancyId);
        }
        loop.listener = listener;
    }

    /**
     * Atomically swaps the desired graph if the current value matches {@code expected}.
     * Uses compare-and-set semantics on the underlying {@link AtomicReference}.
     *
     * @param tenancyId   the tenant identifier
     * @param expected    the expected current desired graph (identity comparison)
     * @param newDesired  the new desired graph to set
     * @return true if the swap succeeded
     * @throws IllegalStateException if no loop is running for this tenant
     */
    public boolean compareAndSetDesired(String tenancyId,
            DesiredStateGraph expected, DesiredStateGraph newDesired) {
        TenantLoop loop = loops.get(tenancyId);
        if (loop == null) {
            throw new IllegalStateException("No reconciliation loop running for tenant: " + tenancyId);
        }
        return loop.desiredRef.compareAndSet(expected, newDesired);
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
     * <p>If the set of node types in the graph changes, interval groups are recomputed:
     * obsolete timers are cancelled and new ones are started for newly-added groups.
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

    /**
     * Requests an out-of-band reconciliation for a tenant. Schedules a debounced
     * reconciliation cycle after the configured debounce window. If called multiple
     * times within the window, the previous scheduled reconciliation is cancelled
     * and rescheduled.
     *
     * <p>This is a no-op if no loop is running for the given tenant.
     *
     * @param tenancyId the tenant identifier
     */
    public void requestReconciliation(String tenancyId) {
        TenantLoop loop = loops.get(tenancyId);
        if (loop != null) {
            loop.scheduleReconciliation();
        }
    }

    /**
     * Returns the current desired state graph for a tenant.
     *
     * @param tenancyId the tenant identifier
     * @return the current desired state graph
     * @throws IllegalStateException if no loop is running for this tenant
     */
    public DesiredStateGraph getDesired(String tenancyId) {
        TenantLoop loop = loops.get(tenancyId);
        if (loop == null) {
            throw new IllegalStateException(
                "No reconciliation loop running for tenant: " + tenancyId);
        }
        return loop.desiredRef.get();
    }

    public java.util.Set<String> tenantIds() {
        return java.util.Set.copyOf(loops.keySet());
    }


    public void shutdown() {
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
     * Computes interval groups for the node types present in the given graph.
     * Types are grouped by their effective resync interval from the router.
     */
    private Map<Duration, Set<NodeType>> computeIntervalGroups(DesiredStateGraph desired) {
        if (router == null) {
            return Map.of();
        }
        Map<Duration, Set<NodeType>> groups = new LinkedHashMap<>();
        Set<NodeType> graphTypes = desired.nodes().values().stream()
            .map(DesiredNode::type)
            .collect(Collectors.toSet());
        for (NodeType type : graphTypes) {
            Duration interval = router.resyncIntervalFor(type);
            groups.computeIfAbsent(interval, k -> new LinkedHashSet<>()).add(type);
        }
        return groups;
    }

    public static class Builder {
        private final TransitionPlanner                  planner;
        private final TransitionExecutor                 executor;
        private final ActualStateAdapterRouter           actualStateAdapterRouter;
        private final FaultPolicyEngine                  faultPolicyEngine;
        private final MergedEventSource                  mergedEventSource;
        private       NodeProvisionerRouter              router;
        private       Duration                           debounceWindow  = DEFAULT_DEBOUNCE;
        private       Duration                           resyncInterval;
        private       Consumer<CloudEvent>               cloudEventSink;
        private       CbrProposalTracker                 cbrTracker;
        private       List<GlobalReconciliationListener> globalListeners = List.of();
        private       ReconciliationStateStore           reconciliationStateStore;
        private       int                                nodeOutcomesThreshold = ReconciliationCompletedData.NODE_OUTCOMES_THRESHOLD;


        private Builder(TransitionPlanner planner, TransitionExecutor executor,
                        ActualStateAdapterRouter actualStateAdapterRouter,
                        FaultPolicyEngine faultPolicyEngine,
                        MergedEventSource mergedEventSource) {
            this.planner                  = planner;
            this.executor                 = executor;
            this.actualStateAdapterRouter = actualStateAdapterRouter;
            this.faultPolicyEngine        = faultPolicyEngine;
            this.mergedEventSource        = mergedEventSource;
        }

        public Builder router(NodeProvisionerRouter router) {
            this.router = router;
            return this;
        }

        public Builder debounceWindow(Duration debounceWindow) {
            this.debounceWindow = debounceWindow;
            return this;
        }

        public Builder resyncInterval(Duration resyncInterval) {
            this.resyncInterval = resyncInterval;
            return this;
        }

        public Builder cloudEventSink(Consumer<CloudEvent> cloudEventSink) {
            this.cloudEventSink = cloudEventSink;
            return this;
        }

        public Builder cbrTracker(CbrProposalTracker cbrTracker) {
            this.cbrTracker = cbrTracker;
            return this;
        }

        public Builder globalListeners(List<GlobalReconciliationListener> globalListeners) {
            this.globalListeners = globalListeners;
            return this;
        }

        public Builder reconciliationStateStore(ReconciliationStateStore reconciliationStateStore) {
            this.reconciliationStateStore = reconciliationStateStore;
            return this;
        }


        public Builder nodeOutcomesThreshold(int nodeOutcomesThreshold) {
            this.nodeOutcomesThreshold = nodeOutcomesThreshold;
            return this;
        }

        public ReconciliationLoop build() {
            return new ReconciliationLoop(planner, executor, actualStateAdapterRouter,
                                          faultPolicyEngine, mergedEventSource, router, debounceWindow,
                                          resyncInterval, cloudEventSink, cbrTracker, globalListeners,
                                          reconciliationStateStore, nodeOutcomesThreshold);
        }
    }

    /**
     * Internal per-tenant reconciliation loop.
     */
    private class TenantLoop {

        private static final String INSTRUMENTATION_NAME = "io.casehub.desiredstate";
        private final String tenancyId;
        private final AtomicReference<DesiredStateGraph> desiredRef;
        private final AtomicLong cycleCounter = new AtomicLong(0);
        // Read-then-modify race is harmless: duplicate ANTI signals are idempotent in RAS Count/Streak evaluation
        private final Set<NodeId> activeProblems = ConcurrentHashMap.newKeySet();
        /** Interval-grouped timers used when router is available and no override. */
        private final Map<Duration, ScheduledFuture<?>> resyncFutures = new ConcurrentHashMap<>();
        private volatile ReconciliationListener listener;
        private volatile Cancellable eventSubscription;
        private volatile ScheduledFuture<?> requestedReconciliation;
        /** Single resync timer used when resyncOverride is set (test mode). */
        private volatile ScheduledFuture<?> resyncFuture;

        TenantLoop(String tenancyId, DesiredStateGraph desired) {
            this(tenancyId, desired, null);
        }

        TenantLoop(String tenancyId, DesiredStateGraph desired, ReconciliationListener listener) {
            this.tenancyId = tenancyId;
            this.desiredRef = new AtomicReference<>(desired);
            this.listener = listener;
        }

        void start() {
            // Immediate initial full-graph reconciliation
            reconcile();

            // Event-driven: subscribe with debounce
            eventSubscription = mergedEventSource.stream()
                .group().intoLists().every(debounceWindow)
                .filter(batch -> !batch.isEmpty())
                .subscribe().with(
                    batch -> reconcile(),
                    failure -> LOG.log(Level.WARNING,
                        "Event stream error for tenant " + tenancyId, failure)
                );

            // Periodic re-sync
            if (resyncOverride != null) {
                // Test mode: single timer at override interval
                long resyncMillis = resyncOverride.toMillis();
                resyncFuture = scheduler.scheduleAtFixedRate(
                    this::reconcile,
                    resyncMillis, resyncMillis, TimeUnit.MILLISECONDS);
            } else if (router != null) {
                // Production mode: interval-grouped timers
                scheduleIntervalGroups(desiredRef.get());
            } else {
                // Legacy fallback: single timer at default interval
                long resyncMillis = DEFAULT_RESYNC.toMillis();
                resyncFuture = scheduler.scheduleAtFixedRate(
                    this::reconcile,
                    resyncMillis, resyncMillis, TimeUnit.MILLISECONDS);
            }
        }

        private void scheduleIntervalGroups(DesiredStateGraph desired) {
            Map<Duration, Set<NodeType>> groups = computeIntervalGroups(desired);
            for (Map.Entry<Duration, Set<NodeType>> group : groups.entrySet()) {
                long millis = group.getKey().toMillis();
                Set<NodeType> types = Set.copyOf(group.getValue());
                ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                    () -> reconcileTypes(types),
                    millis, millis, TimeUnit.MILLISECONDS);
                resyncFutures.put(group.getKey(), future);
            }
        }

        void stop() {
            if (eventSubscription != null) {
                eventSubscription.cancel();
            }
            for (GlobalReconciliationListener gl : globalListeners) {
                try {
                    gl.onTenantStopped(tenancyId);
                } catch (Exception e) {
                    LOG.log(Level.WARNING,
                            "Global listener onTenantStopped failed for tenant " + tenancyId, e);
                }
            }
            if (resyncFuture != null) {
                resyncFuture.cancel(false);
            }
            for (ScheduledFuture<?> future : resyncFutures.values()) {
                future.cancel(false);
            }
            resyncFutures.clear();
            if (requestedReconciliation != null) {
                requestedReconciliation.cancel(false);
            }
            cbrTracker.clearTenant(tenancyId);
            reconciliationStateStore.remove(tenancyId);
        }

        void updateDesired(DesiredStateGraph newDesired) {
            DesiredStateGraph old = desiredRef.getAndSet(newDesired);

            // Recompute interval groups if using router-driven scheduling and types changed
            if (resyncOverride == null && router != null) {
                Set<NodeType> oldTypes = old.nodes().values().stream()
                    .map(DesiredNode::type)
                    .collect(Collectors.toSet());
                Set<NodeType> newTypes = newDesired.nodes().values().stream()
                    .map(DesiredNode::type)
                    .collect(Collectors.toSet());
                if (!oldTypes.equals(newTypes)) {
                    synchronized (this) {
                        // Cancel all existing interval group timers
                        for (ScheduledFuture<?> future : resyncFutures.values()) {
                            future.cancel(false);
                        }
                        resyncFutures.clear();
                        // Schedule new interval groups
                        scheduleIntervalGroups(newDesired);
                    }
                }
            }
        }

        void scheduleReconciliation() {
            synchronized (this) {
                if (requestedReconciliation != null && !requestedReconciliation.isDone()) {
                    requestedReconciliation.cancel(false);
                }
                requestedReconciliation = scheduler.schedule(
                    this::reconcile,
                    debounceWindow.toMillis(),
                    TimeUnit.MILLISECONDS
                );
            }
        }

        private void fireGlobalListeners(DesiredStateGraph desired, ActualState actual) {
            for (GlobalReconciliationListener gl : globalListeners) {
                try {
                    gl.onReconciliationCycleCompleted(tenancyId, desired, actual);
                } catch (Exception e) {
                    LOG.log(Level.WARNING,
                            "Global reconciliation listener failed for tenant " + tenancyId, e);
                }
            }
        }

        private void firePerTenantListener(DesiredStateGraph desired, ActualState actual) {
            ReconciliationListener l = listener;
            if (l != null) {
                try {
                    l.onReconciliationCycleCompleted(tenancyId, desired, actual);
                } catch (Exception e) {
                    LOG.log(Level.WARNING,
                            "Reconciliation listener failed for tenant " + tenancyId, e);
                }
            }
        }

        /**
         * Full-graph reconciliation. Used by event-driven path and initial reconciliation.
         */
        private void reconcile() {
            Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
            Span reconcileSpan = tracer.spanBuilder("reconcile")
                    .setAttribute(AttributeKey.stringKey("desiredstate.tenant.id"), tenancyId)
                    .startSpan();
            try (Scope ignored = reconcileSpan.makeCurrent()) {
                DesiredStateGraph desired = desiredRef.get();

                ActualState actual = readActual(desired, tenancyId);

                // Listener fires unconditionally — including empty-plan cycles
                fireGlobalListeners(desired, actual);
                firePerTenantListener(desired, actual);

                Set<NodeId> driftedNodes = new HashSet<>();
                desired = detectDrift(desired, actual, driftedNodes);

                TransitionPlan plan = plan(desired, actual);
                if (plan.isEmpty()) {
                    TransitionResult emptyResult = new TransitionResult(Map.of());
                    List<CbrOutcomeData> cbrOutcomes = cbrTracker.matchOutcomes(
                        tenancyId, emptyResult, desired);
                    if (!driftedNodes.isEmpty() || !activeProblems.isEmpty()) {
                        emitCycleEvents(desired, plan, emptyResult, actual, driftedNodes);
                    }
                    emitCbrOutcomeEvents(cbrOutcomes);
                    return;
                }

                TransitionResult result = execute(plan, tenancyId);

                List<CbrOutcomeData> cbrOutcomes = cbrTracker.matchOutcomes(
                    tenancyId, result, desired);

                faultFeedback(desired, plan, result, actual);

                emitCycleEvents(desired, plan, result, actual, driftedNodes);
                emitCbrOutcomeEvents(cbrOutcomes);
            } catch (Exception e) {
                reconcileSpan.setStatus(StatusCode.ERROR, e.getMessage());
                reconcileSpan.recordException(e);
                LOG.log(Level.WARNING,
                        "Reconciliation cycle failed for tenant " + tenancyId, e);
            } finally {
                reconcileSpan.end();
            }
        }

        /**
         * Type-filtered reconciliation. Filters the desired graph to nodes of the given
         * types and reconciles only those nodes. Used by interval-grouped timers.
         */
        private void reconcileTypes(Set<NodeType> types) {
            Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
            Span reconcileSpan = tracer.spanBuilder("reconcile")
                    .setAttribute(AttributeKey.stringKey("desiredstate.tenant.id"), tenancyId)
                    .setAttribute(AttributeKey.stringArrayKey("desiredstate.reconcile.types"),
                        types.stream().map(NodeType::value).sorted().toList())
                    .startSpan();
            try (Scope ignored = reconcileSpan.makeCurrent()) {
                DesiredStateGraph fullDesired = desiredRef.get();
                DesiredStateGraph filteredDesired = fullDesired.filterByTypes(types);

                if (filteredDesired.isEmpty()) {
                    return;
                }

                ActualState actual = readActual(filteredDesired, tenancyId);

                Set<NodeId> driftedNodes = new HashSet<>();
                filteredDesired = detectDrift(filteredDesired, actual, driftedNodes);

                TransitionPlan plan = plan(filteredDesired, actual);
                if (plan.isEmpty()) {
                    // Type-filtered reconciliation checks global activeProblems — intentional,
                    // cross-type recovery detection is correct (node recovery is independent of which type-filter cycle detects it)
                    if (!driftedNodes.isEmpty() || !activeProblems.isEmpty()) {
                        TransitionResult emptyResult = new TransitionResult(Map.of());
                        emitCycleEvents(filteredDesired, plan, emptyResult, actual, driftedNodes);
                    }
                    return;
                }

                TransitionResult result = execute(plan, tenancyId);

                faultFeedback(filteredDesired, plan, result, actual);

                emitCycleEvents(filteredDesired, plan, result, actual, driftedNodes);
            } catch (Exception e) {
                reconcileSpan.setStatus(StatusCode.ERROR, e.getMessage());
                reconcileSpan.recordException(e);
                LOG.log(Level.WARNING,
                        "Type-filtered reconciliation cycle failed for tenant " + tenancyId
                        + " types " + types, e);
            } finally {
                reconcileSpan.end();
            }
        }

        private ActualState readActual(DesiredStateGraph desired, String tenancyId) {
            Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("readActual").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                ActualState actual = actualStateAdapterRouter.readActual(desired, tenancyId);
                span.setAttribute(AttributeKey.longKey("desiredstate.node.count"),
                        actual.statuses().size());
                return actual;
            } finally {
                span.end();
            }
        }

        private DesiredStateGraph detectDrift(DesiredStateGraph desired, ActualState actual,
                                              Set<NodeId> driftedNodesOut) {
            boolean hasDrift = desired.nodes().entrySet().stream()
                    .anyMatch(e -> actual.statuses().getOrDefault(e.getKey(), NodeStatus.UNKNOWN) == NodeStatus.DRIFTED);
            if (!hasDrift) {
                return desired;
            }

            Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("detectDrift").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                int driftCount = 0;
                List<GraphMutation<DesiredNode>> mutations = new ArrayList<>();
                DesiredStateGraph mutated = desired;
                for (Map.Entry<NodeId, DesiredNode> entry : desired.nodes().entrySet()) {
                    NodeStatus status = actual.statuses().getOrDefault(entry.getKey(), NodeStatus.UNKNOWN);
                    if (status == NodeStatus.DRIFTED) {
                        driftCount++;
                        driftedNodesOut.add(entry.getKey());
                        FaultEvent faultEvent = new FaultEvent(
                                entry.getKey(), FaultType.NODE_DEGRADED, "Node drifted from desired spec");
                        List<GraphMutation<DesiredNode>> faultMutations = faultPolicyEngine.evaluate(tenancyId, faultEvent, mutated, actual);
                        mutations.addAll(faultMutations);
                        for (GraphMutation mutation : faultMutations) {
                            mutated = mutated.withMutation(mutation);
                        }
                    }
                }
                span.setAttribute(AttributeKey.longKey("desiredstate.drift.count"), driftCount);
                if (!mutations.isEmpty()) {
                    casRetryMutations(mutations);
                }
                return mutated;
            } finally {
                span.end();
            }
        }

        private TransitionPlan plan(DesiredStateGraph desired, ActualState actual) {
            Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("plan").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                DesiredStateGraph previousDesired = reconciliationStateStore.load(tenancyId).orElse(null);
                TransitionPlan plan = planner.plan(desired, actual, previousDesired,
                                                   type -> router != null && router.supportsStatefulLifecycle(type));
                reconciliationStateStore.store(tenancyId, desired);
                span.setAttribute(AttributeKey.longKey("desiredstate.additions"),
                                  plan.flatAdditions().size());
                span.setAttribute(AttributeKey.longKey("desiredstate.removals"),
                                  plan.flatRemovals().size());
                span.setAttribute(AttributeKey.longKey("desiredstate.suspensions"),
                                  plan.flatSuspensions().size());
                span.setAttribute(AttributeKey.longKey("desiredstate.resumptions"),
                                  plan.flatResumptions().size());
                return plan;
            } finally {
                span.end();
            }
        }

        private TransitionResult execute(TransitionPlan plan, String tenancyId) {
            Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("execute").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                return executor.execute(plan, tenancyId);
            } finally {
                span.end();
            }
        }

        private void faultFeedback(DesiredStateGraph desired, TransitionPlan plan,
                                   TransitionResult result, ActualState actual) {
            boolean hasFaultyOutcomes = result.outcomes().values().stream()
                                              .anyMatch(o -> o instanceof StepOutcome.Failed || o instanceof StepOutcome.Rejected);
            if (!hasFaultyOutcomes) {
                return;
            }

            Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("faultFeedback").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                Map<NodeId, StepAction> plannedActions = new HashMap<>();
                for (OrderedStep step : plan.flatRemovals()) {plannedActions.put(step.node().id(), StepAction.DEPROVISION);}
                for (OrderedStep step : plan.flatSuspensions()) {plannedActions.put(step.node().id(), StepAction.SUSPEND);}
                for (OrderedStep step : plan.flatResumptions()) {plannedActions.put(step.node().id(), StepAction.RESUME);}
                for (OrderedStep step : plan.flatAdditions()) {plannedActions.put(step.node().id(), StepAction.PROVISION);}

                int                              faultCount    = 0;
                int                              mutationCount = 0;
                List<GraphMutation<DesiredNode>> mutations     = new ArrayList<>();
                DesiredStateGraph                mutated       = desired;
                for (Map.Entry<NodeId, StepOutcome> entry : result.outcomes().entrySet()) {
                    if (entry.getValue() instanceof StepOutcome.Failed failed) {
                        faultCount++;
                        FaultType faultType = switch (plannedActions.getOrDefault(entry.getKey(), StepAction.PROVISION)) {
                            case PROVISION -> FaultType.PROVISION_FAILED;
                            case DEPROVISION -> FaultType.DEPROVISION_FAILED;
                            case SUSPEND -> FaultType.SUSPEND_FAILED;
                            case RESUME -> FaultType.RESUME_FAILED;
                        };
                        FaultEvent faultEvent = new FaultEvent(
                                entry.getKey(), faultType, failed.reason());
                        List<GraphMutation<DesiredNode>> faultMutations = faultPolicyEngine.evaluate(tenancyId, faultEvent, mutated, actual);
                        mutationCount += faultMutations.size();
                        mutations.addAll(faultMutations);
                        for (GraphMutation mutation : faultMutations) {
                            mutated = mutated.withMutation(mutation);
                        }
                    } else if (entry.getValue() instanceof StepOutcome.Rejected rejected) {
                        faultCount++;
                        FaultEvent faultEvent = new FaultEvent(
                                entry.getKey(), FaultType.APPROVAL_REJECTED, rejected.reason());
                        List<GraphMutation<DesiredNode>> faultMutations = faultPolicyEngine.evaluate(tenancyId, faultEvent, mutated, actual);
                        mutationCount += faultMutations.size();
                        mutations.addAll(faultMutations);
                        for (GraphMutation mutation : faultMutations) {
                            mutated = mutated.withMutation(mutation);
                        }
                    }
                }
                span.setAttribute(AttributeKey.longKey("desiredstate.fault.count"), faultCount);
                span.setAttribute(AttributeKey.longKey("desiredstate.mutation.count"), mutationCount);
                if (!mutations.isEmpty()) {
                    casRetryMutations(mutations);
                }
            } finally {
                span.end();
            }
        }

        /**
         * CAS merge-and-retry loop. Re-reads the current desired graph, applies all
         * accumulated mutations, and retries if the ref was updated concurrently.
         * Mutations are graph-structural and safely re-applicable to any graph version.
         */
        private void casRetryMutations(List<GraphMutation<DesiredNode>> mutations) {
            DesiredStateGraph current;
            DesiredStateGraph updated;
            do {
                current = desiredRef.get();
                updated = current;
                for (GraphMutation mutation : mutations) {
                    updated = updated.withMutation(mutation);
                }
            } while (updated != current && !desiredRef.compareAndSet(current, updated));
        }

        /**
         * Emit CloudEvents for this reconciliation cycle.
         * Called at cycle end after faultFeedback completes.
         */
        private void emitCycleEvents(DesiredStateGraph desired, TransitionPlan plan,
                                     TransitionResult result, ActualState actual,
                                     Set<NodeId> driftedNodes) {
            long             version = cycleCounter.incrementAndGet();
            List<CloudEvent> events  = new ArrayList<>();

            Map<NodeId, DesiredNode> planNodes = new HashMap<>();
            for (OrderedStep step : plan.flatRemovals()) {planNodes.put(step.node().id(), step.node());}
            for (OrderedStep step : plan.flatSuspensions()) {planNodes.put(step.node().id(), step.node());}
            for (OrderedStep step : plan.flatResumptions()) {planNodes.put(step.node().id(), step.node());}
            for (OrderedStep step : plan.flatAdditions()) {planNodes.put(step.node().id(), step.node());}

            Map<NodeId, StepAction> plannedActions = new HashMap<>();
            for (OrderedStep step : plan.flatRemovals()) {plannedActions.put(step.node().id(), StepAction.DEPROVISION);}
            for (OrderedStep step : plan.flatSuspensions()) {plannedActions.put(step.node().id(), StepAction.SUSPEND);}
            for (OrderedStep step : plan.flatResumptions()) {plannedActions.put(step.node().id(), StepAction.RESUME);}
            for (OrderedStep step : plan.flatAdditions()) {plannedActions.put(step.node().id(), StepAction.PROVISION);}

            Set<NodeId> recovered = new HashSet<>();
            for (NodeId problemNode : activeProblems) {
                NodeStatus  status      = actual.statuses().get(problemNode);
                DesiredNode desiredNode = desired.nodes().get(problemNode);
                boolean     isRecovered;
                if (desiredNode != null && desiredNode.targetStatus() == TargetStatus.SUSPENDED) {
                    isRecovered = status == NodeStatus.SUSPENDED;
                } else {
                    isRecovered = status == NodeStatus.PRESENT;
                }
                if (isRecovered) {
                    recovered.add(problemNode);
                    String parentNodeId = resolveParent(desired, problemNode);
                    if (desiredNode != null) {
                        NodeRecoveredData data = new NodeRecoveredData(
                                tenancyId, problemNode.value(), desiredNode.type().value(),
                                version, parentNodeId);
                        events.add(eventEmitter.nodeRecovered(data));
                    }
                }
            }
            activeProblems.removeAll(recovered);

            for (NodeId nodeId : driftedNodes) {
                DesiredNode node = desired.nodes().get(nodeId);
                if (node != null) {
                    String parentNodeId = resolveParent(desired, nodeId);
                    NodeDriftedData data = new NodeDriftedData(
                            tenancyId, nodeId.value(), node.type().value(),
                            version, parentNodeId);
                    events.add(eventEmitter.nodeDrifted(data));
                    activeProblems.add(nodeId);
                }
            }

            for (Map.Entry<NodeId, StepOutcome> entry : result.outcomes().entrySet()) {
                if (entry.getValue() instanceof StepOutcome.Failed failed) {
                    DesiredNode node = planNodes.getOrDefault(entry.getKey(),
                                                              desired.nodes().get(entry.getKey()));
                    if (node != null) {
                        String parentNodeId = resolveParent(desired, entry.getKey());
                        String faultType = switch (plannedActions.getOrDefault(entry.getKey(), StepAction.PROVISION)) {
                            case PROVISION -> "PROVISION_FAILED";
                            case DEPROVISION -> "DEPROVISION_FAILED";
                            case SUSPEND -> "SUSPEND_FAILED";
                            case RESUME -> "RESUME_FAILED";
                        };
                        NodeFaultedData data = new NodeFaultedData(
                                tenancyId, entry.getKey().value(), node.type().value(),
                                faultType, failed.reason(), version, parentNodeId);
                        events.add(eventEmitter.nodeFaulted(data));
                        activeProblems.add(entry.getKey());
                    }
                } else if (entry.getValue() instanceof StepOutcome.Rejected rejected) {
                    DesiredNode node = planNodes.getOrDefault(entry.getKey(),
                                                              desired.nodes().get(entry.getKey()));
                    if (node != null) {
                        String parentNodeId = resolveParent(desired, entry.getKey());
                        NodeFaultedData data = new NodeFaultedData(
                                tenancyId, entry.getKey().value(), node.type().value(),
                                "APPROVAL_REJECTED", rejected.reason(), version, parentNodeId);
                        events.add(eventEmitter.nodeFaulted(data));
                        activeProblems.add(entry.getKey());
                    }
                } else if (entry.getValue() instanceof StepOutcome.AlreadyConverged) {
                    DesiredNode node = planNodes.getOrDefault(entry.getKey(),
                                                              desired.nodes().get(entry.getKey()));
                    if (node != null) {
                        String parentNodeId = resolveParent(desired, entry.getKey());
                        NodeAlreadyConvergedData data = new NodeAlreadyConvergedData(
                                tenancyId, entry.getKey().value(), node.type().value(),
                                version, parentNodeId);
                        events.add(eventEmitter.nodeAlreadyConverged(data));
                    }
                }
            }

            int faultCount = (int) result.outcomes().values().stream()
                                         .filter(o -> o instanceof StepOutcome.Failed || o instanceof StepOutcome.Rejected)
                                         .count();
            Map<String, String> nodeOutcomes;
            if (result.outcomes().size() <= nodeOutcomesThreshold) {
                nodeOutcomes = new LinkedHashMap<>();
                for (Map.Entry<NodeId, StepOutcome> entry2 : result.outcomes().entrySet()) {
                    nodeOutcomes.put(entry2.getKey().value(), outcomeLabel(entry2.getValue()));
                }
                nodeOutcomes = Map.copyOf(nodeOutcomes);
            } else {
                nodeOutcomes = Map.of();
            }
            ReconciliationCompletedData completedData = new ReconciliationCompletedData(
                    tenancyId, version, desired.nodes().size(),
                    plan.flatAdditions().size(), plan.flatRemovals().size(),
                    plan.flatSuspensions().size(), plan.flatResumptions().size(),
                    faultCount, nodeOutcomes, Instant.now());
            events.add(eventEmitter.reconciliationCompleted(completedData));

            events.forEach(cloudEventSink);
        }

        private void emitCbrOutcomeEvents(List<CbrOutcomeData> outcomes) {
            for (CbrOutcomeData outcome : outcomes) {
                cloudEventSink.accept(eventEmitter.cbrOutcome(outcome));
            }
        }


        /**
         * Resolve the parent node ID for a given node.
         * Returns the first dependency (if any), or null for root nodes.
         * For nodes with multiple dependencies, the parent is arbitrary (Set iteration order is non-deterministic).
         */
        private String resolveParent(DesiredStateGraph graph, NodeId nodeId) {
            Set<NodeId> deps = graph.dependenciesOf(nodeId);
            if (deps.isEmpty()) {
                return null;
            }
            return deps.iterator().next().value();
        }

        private static String outcomeLabel(StepOutcome outcome) {
            return switch (outcome) {
                case StepOutcome.Succeeded ignored -> "SUCCEEDED";
                case StepOutcome.AlreadyConverged ignored -> "ALREADY_CONVERGED";
                case StepOutcome.Failed ignored -> "FAILED";
                case StepOutcome.Skipped ignored -> "SKIPPED";
                case StepOutcome.Rejected ignored -> "REJECTED";
            };
        }

    }
}

