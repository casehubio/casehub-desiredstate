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

        private static final String INSTRUMENTATION_NAME = "io.casehub.desiredstate";

        private void reconcile() {
            Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
            Span reconcileSpan = tracer.spanBuilder("reconcile")
                    .setAttribute(AttributeKey.stringKey("desiredstate.tenant.id"), tenancyId)
                    .startSpan();
            try (Scope ignored = reconcileSpan.makeCurrent()) {
                DesiredStateGraph desired = desiredRef.get();

                ActualState actual = readActual(desired);

                desired = detectDrift(desired, actual);

                TransitionPlan plan = plan(desired, actual);
                if (plan.isEmpty()) {
                    return;
                }

                TransitionResult result = execute(plan);

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

        private ActualState readActual(DesiredStateGraph desired) {
            Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("readActual").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                ActualState actual = actualStateAdapter.readActual(desired);
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
                DesiredStateGraph mutated = desired;
                for (Map.Entry<NodeId, DesiredNode> entry : desired.nodes().entrySet()) {
                    NodeStatus status = actual.statuses().getOrDefault(entry.getKey(), NodeStatus.UNKNOWN);
                    if (status == NodeStatus.DRIFTED) {
                        driftCount++;
                        FaultEvent faultEvent = new FaultEvent(
                                entry.getKey(), FaultType.NODE_DEGRADED, "Node drifted from desired spec");
                        List<GraphMutation> mutations = faultPolicyEngine.evaluate(faultEvent, mutated);
                        for (GraphMutation mutation : mutations) {
                            mutated = mutated.withMutation(mutation);
                        }
                    }
                }
                span.setAttribute(AttributeKey.longKey("desiredstate.drift.count"), driftCount);
                if (mutated != desired) {
                    desiredRef.compareAndSet(desired, mutated);
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

        private TransitionResult execute(TransitionPlan plan) {
            Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME).spanBuilder("execute").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                return executor.execute(plan).await().indefinitely();
            } finally {
                span.end();
            }
        }

        private void faultFeedback(DesiredStateGraph desired, TransitionPlan plan,
                                   TransitionResult result) {
            boolean hasFailures = result.outcomes().values().stream()
                    .anyMatch(o -> o instanceof StepOutcome.Failed);
            if (!hasFailures) {
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
                DesiredStateGraph mutated = desired;
                for (Map.Entry<NodeId, StepOutcome> entry : result.outcomes().entrySet()) {
                    if (entry.getValue() instanceof StepOutcome.Failed failed) {
                        faultCount++;
                        FaultType faultType = removalNodeIds.contains(entry.getKey())
                                ? FaultType.DEPROVISION_FAILED
                                : FaultType.PROVISION_FAILED;
                        FaultEvent faultEvent = new FaultEvent(
                                entry.getKey(), faultType, failed.reason());
                        List<GraphMutation> mutations = faultPolicyEngine.evaluate(faultEvent, mutated);
                        mutationCount += mutations.size();
                        for (GraphMutation mutation : mutations) {
                            mutated = mutated.withMutation(mutation);
                        }
                    }
                }
                span.setAttribute(AttributeKey.longKey("desiredstate.fault.count"), faultCount);
                span.setAttribute(AttributeKey.longKey("desiredstate.mutation.count"), mutationCount);
                if (mutated != desired) {
                    desiredRef.compareAndSet(desired, mutated);
                }
            } finally {
                span.end();
            }
        }
    }
}
