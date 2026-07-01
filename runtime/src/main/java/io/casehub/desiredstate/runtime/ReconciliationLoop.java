package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
 *   <li><b>Event-driven:</b> subscribes to {@link EventSource#stream()} and debounces
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
    private final NodeProvisionerRouter router;
    private final Duration debounceWindow;
    private final Duration resyncOverride;

    private final ConcurrentHashMap<String, TenantLoop> loops = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    /**
     * CDI constructor with router-driven interval-grouped scheduling.
     */
    @Inject
    public ReconciliationLoop(
            TransitionPlanner planner,
            TransitionExecutor executor,
            ActualStateAdapter actualStateAdapter,
            FaultPolicyEngine faultPolicyEngine,
            EventSource eventSource,
            NodeProvisionerRouter router) {
        this(planner, executor, actualStateAdapter, faultPolicyEngine, eventSource,
             router, DEFAULT_DEBOUNCE, null);
    }

    /**
     * Test-friendly constructor with router and debounce control, using interval-grouped
     * scheduling derived from the router.
     */
    public ReconciliationLoop(
            TransitionPlanner planner,
            TransitionExecutor executor,
            ActualStateAdapter actualStateAdapter,
            FaultPolicyEngine faultPolicyEngine,
            EventSource eventSource,
            NodeProvisionerRouter router,
            Duration debounceWindow) {
        this(planner, executor, actualStateAdapter, faultPolicyEngine, eventSource,
             router, debounceWindow, null);
    }

    /**
     * Test-friendly constructor accepting configurable durations.
     * Uses a single resync timer at the given interval, bypassing interval-grouped scheduling.
     * Pass {@code null} for the router when using this constructor.
     */
    public ReconciliationLoop(
            TransitionPlanner planner,
            TransitionExecutor executor,
            ActualStateAdapter actualStateAdapter,
            FaultPolicyEngine faultPolicyEngine,
            EventSource eventSource,
            Duration debounceWindow,
            Duration resyncInterval) {
        this(planner, executor, actualStateAdapter, faultPolicyEngine, eventSource,
             null, debounceWindow, resyncInterval);
    }

    /**
     * CDI constructor with default timers and no router. Kept for backward compatibility.
     */
    public ReconciliationLoop(
            TransitionPlanner planner,
            TransitionExecutor executor,
            ActualStateAdapter actualStateAdapter,
            FaultPolicyEngine faultPolicyEngine,
            EventSource eventSource) {
        this(planner, executor, actualStateAdapter, faultPolicyEngine, eventSource,
             null, DEFAULT_DEBOUNCE, DEFAULT_RESYNC);
    }

    /**
     * Internal master constructor.
     *
     * @param router          provisioner router for interval-grouped scheduling (may be null)
     * @param debounceWindow  debounce window for event-driven and requested reconciliation
     * @param resyncOverride  if non-null, bypasses interval-grouped scheduling with a single timer
     */
    private ReconciliationLoop(
            TransitionPlanner planner,
            TransitionExecutor executor,
            ActualStateAdapter actualStateAdapter,
            FaultPolicyEngine faultPolicyEngine,
            EventSource eventSource,
            NodeProvisionerRouter router,
            Duration debounceWindow,
            Duration resyncOverride) {
        this.planner = planner;
        this.executor = executor;
        this.actualStateAdapter = actualStateAdapter;
        this.faultPolicyEngine = faultPolicyEngine;
        this.eventSource = eventSource;
        this.router = router;
        this.debounceWindow = debounceWindow;
        this.resyncOverride = resyncOverride;

        int poolSize = computeSchedulerPoolSize();
        this.scheduler = Executors.newScheduledThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "reconciliation-scheduler");
            t.setDaemon(true);
            return t;
        });
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

    /**
     * Builds a filtered graph containing only nodes whose type is in the target set,
     * plus dependencies between those nodes.
     */
    private DesiredStateGraph filterGraph(DesiredStateGraph full, Set<NodeType> types) {
        DesiredStateGraph filtered = ImmutableDesiredStateGraph.empty();
        for (DesiredNode node : full.nodes().values()) {
            if (types.contains(node.type())) {
                filtered = filtered.withNode(node);
            }
        }
        // Add dependencies where both endpoints are in the filtered graph
        for (Dependency dep : full.dependencies()) {
            if (filtered.nodes().containsKey(dep.from()) && filtered.nodes().containsKey(dep.to())) {
                filtered = filtered.withDependency(dep);
            }
        }
        return filtered;
    }

    /**
     * Internal per-tenant reconciliation loop.
     */
    private class TenantLoop {

        private final String tenancyId;
        private final AtomicReference<DesiredStateGraph> desiredRef;
        private volatile Cancellable eventSubscription;
        private volatile ScheduledFuture<?> requestedReconciliation;

        /** Single resync timer used when resyncOverride is set (test mode). */
        private volatile ScheduledFuture<?> resyncFuture;

        /** Interval-grouped timers used when router is available and no override. */
        private final Map<Duration, ScheduledFuture<?>> resyncFutures = new ConcurrentHashMap<>();

        TenantLoop(String tenancyId, DesiredStateGraph desired) {
            this.tenancyId = tenancyId;
            this.desiredRef = new AtomicReference<>(desired);
        }

        void start() {
            // Immediate initial full-graph reconciliation
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

        private static final String INSTRUMENTATION_NAME = "io.casehub.desiredstate";

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

                desired = detectDrift(desired, actual);

                TransitionPlan plan = plan(desired, actual);
                if (plan.isEmpty()) {
                    return;
                }

                TransitionResult result = execute(plan, tenancyId);

                faultFeedback(desired, plan, result);
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
                DesiredStateGraph filteredDesired = filterGraph(fullDesired, types);

                if (filteredDesired.isEmpty()) {
                    return;
                }

                ActualState actual = readActual(filteredDesired, tenancyId);

                filteredDesired = detectDrift(filteredDesired, actual);

                TransitionPlan plan = plan(filteredDesired, actual);
                if (plan.isEmpty()) {
                    return;
                }

                TransitionResult result = execute(plan, tenancyId);

                faultFeedback(filteredDesired, plan, result);
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
                ActualState actual = actualStateAdapter.readActual(desired, tenancyId);
                span.setAttribute(AttributeKey.longKey("desiredstate.node.count"),
                        actual.statuses().size());
                return actual;
            } finally {
                span.end();
            }
        }

        private DesiredStateGraph detectDrift(DesiredStateGraph desired, ActualState actual) {
            boolean hasDrift = desired.nodes().entrySet().stream()
                    .anyMatch(e -> actual.statuses().getOrDefault(e.getKey(), NodeStatus.UNKNOWN) == NodeStatus.DRIFTED);
            if (!hasDrift) {
                return desired;
            }

            Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("detectDrift").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                int driftCount = 0;
                List<GraphMutation> mutations = new ArrayList<>();
                DesiredStateGraph mutated = desired;
                for (Map.Entry<NodeId, DesiredNode> entry : desired.nodes().entrySet()) {
                    NodeStatus status = actual.statuses().getOrDefault(entry.getKey(), NodeStatus.UNKNOWN);
                    if (status == NodeStatus.DRIFTED) {
                        driftCount++;
                        FaultEvent faultEvent = new FaultEvent(
                                entry.getKey(), FaultType.NODE_DEGRADED, "Node drifted from desired spec");
                        List<GraphMutation> faultMutations = faultPolicyEngine.evaluate(faultEvent, mutated);
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
                TransitionPlan plan = planner.plan(desired, actual);
                span.setAttribute(AttributeKey.longKey("desiredstate.additions"),
                        plan.additions().size());
                span.setAttribute(AttributeKey.longKey("desiredstate.removals"),
                        plan.removals().size());
                return plan;
            } finally {
                span.end();
            }
        }

        private TransitionResult execute(TransitionPlan plan, String tenancyId) {
            Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("execute").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                return executor.execute(plan, tenancyId).await().indefinitely();
            } finally {
                span.end();
            }
        }

        private void faultFeedback(DesiredStateGraph desired, TransitionPlan plan,
                                   TransitionResult result) {
            boolean hasFaultyOutcomes = result.outcomes().values().stream()
                    .anyMatch(o -> o instanceof StepOutcome.Failed || o instanceof StepOutcome.Rejected);
            if (!hasFaultyOutcomes) {
                return;
            }

            Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("faultFeedback").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                Set<NodeId> removalNodeIds = new HashSet<>();
                for (OrderedStep step : plan.removals()) {
                    removalNodeIds.add(step.node().id());
                }

                int faultCount = 0;
                int mutationCount = 0;
                List<GraphMutation> mutations = new ArrayList<>();
                DesiredStateGraph mutated = desired;
                for (Map.Entry<NodeId, StepOutcome> entry : result.outcomes().entrySet()) {
                    if (entry.getValue() instanceof StepOutcome.Failed failed) {
                        faultCount++;
                        FaultType faultType = removalNodeIds.contains(entry.getKey())
                                ? FaultType.DEPROVISION_FAILED
                                : FaultType.PROVISION_FAILED;
                        FaultEvent faultEvent = new FaultEvent(
                                entry.getKey(), faultType, failed.reason());
                        List<GraphMutation> faultMutations = faultPolicyEngine.evaluate(faultEvent, mutated);
                        mutationCount += faultMutations.size();
                        mutations.addAll(faultMutations);
                        for (GraphMutation mutation : faultMutations) {
                            mutated = mutated.withMutation(mutation);
                        }
                    } else if (entry.getValue() instanceof StepOutcome.Rejected rejected) {
                        faultCount++;
                        FaultEvent faultEvent = new FaultEvent(
                                entry.getKey(), FaultType.APPROVAL_REJECTED, rejected.reason());
                        List<GraphMutation> faultMutations = faultPolicyEngine.evaluate(faultEvent, mutated);
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
        private void casRetryMutations(List<GraphMutation> mutations) {
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
    }
}
