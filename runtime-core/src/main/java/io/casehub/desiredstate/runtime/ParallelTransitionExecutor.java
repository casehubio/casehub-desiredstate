package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeProvisionerRouter;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionExecutor;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.api.TransitionResult;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;

public class ParallelTransitionExecutor implements TransitionExecutor {

    private static final Logger LOG = Logger.getLogger(ParallelTransitionExecutor.class.getName());
    private static final String INSTRUMENTATION_NAME = "io.casehub.desiredstate";

    private final NodeStepExecutor nodeStepExecutor;
    private final NodeProvisionerRouter router;
    private final Duration layerTimeout;

    public ParallelTransitionExecutor(NodeStepExecutor nodeStepExecutor,
                                      NodeProvisionerRouter router,
                                      Duration layerTimeout) {
        this.nodeStepExecutor = nodeStepExecutor;
        this.router = router;
        this.layerTimeout = layerTimeout;
    }

    @Override
    public TransitionResult execute(TransitionPlan plan, String tenancyId) {
        Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();
        Set<NodeId> failedNodes = ConcurrentHashMap.newKeySet();

        executePhase(plan.removals(), plan.before(), tenancyId, outcomes, failedNodes,
                nodeId -> plan.before().dependentsOf(nodeId));
        executePhase(plan.suspensions(), plan.before(), tenancyId, outcomes, failedNodes,
                nodeId -> plan.before().dependentsOf(nodeId));
        executePhase(plan.resumptions(), plan.after(), tenancyId, outcomes, failedNodes,
                nodeId -> plan.after().dependenciesOf(nodeId));
        executePhase(plan.additions(), plan.after(), tenancyId, outcomes, failedNodes,
                nodeId -> plan.after().dependenciesOf(nodeId));

        return new TransitionResult(outcomes);
    }

    private void executePhase(List<List<OrderedStep>> layers, DesiredStateGraph graph,
                              String tenancyId, Map<NodeId, StepOutcome> outcomes,
                              Set<NodeId> failedNodes,
                              Function<NodeId, Set<NodeId>> failurePropagationNeighbors) {
        for (List<OrderedStep> layer : layers) {
            executeLayer(layer, graph, tenancyId, outcomes, failedNodes, failurePropagationNeighbors);
        }
    }

    private void executeLayer(List<OrderedStep> layer, DesiredStateGraph graph,
                              String tenancyId, Map<NodeId, StepOutcome> outcomes,
                              Set<NodeId> failedNodes,
                              Function<NodeId, Set<NodeId>> failurePropagationNeighbors) {
        if (layer.isEmpty()) {return;}

        Map<NodeType, Semaphore> semaphores    = buildSemaphores(layer);
        Map<NodeId, StepOutcome> layerOutcomes = new ConcurrentHashMap<>();
        CountDownLatch           latch         = new CountDownLatch(layer.size());
        Context                  otelContext   = Context.current();

        Span layerSpan = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME)
                                            .spanBuilder("parallel-layer")
                                            .setAttribute("desiredstate.layer.size", layer.size())
                                            .startSpan();

        var executorService = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (OrderedStep step : layer) {
                executorService.submit(() -> {
                    try (Scope scope = otelContext.with(layerSpan).makeCurrent()) {
                        executeStepInThread(step, graph, tenancyId, layerOutcomes,
                                            failedNodes, failurePropagationNeighbors, semaphores);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed;
            try {
                completed = latch.await(layerTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                completed = false;
            }

            if (!completed) {
                layerSpan.setStatus(StatusCode.ERROR, "layer timeout exceeded");
                for (OrderedStep step : layer) {
                    NodeId nodeId = step.node().id();
                    layerOutcomes.putIfAbsent(nodeId,
                                              new StepOutcome.Failed("layer timeout exceeded"));
                    failedNodes.add(nodeId);
                }
                executorService.shutdownNow();
            }
        } finally {
            executorService.close();
            layerSpan.end();
        }

        for (OrderedStep step : layer) {
            StepOutcome outcome = layerOutcomes.get(step.node().id());
            if (outcome != null) {
                outcomes.put(step.node().id(), outcome);
            }
        }
    }

    private void executeStepInThread(OrderedStep step, DesiredStateGraph graph,
                                     String tenancyId, Map<NodeId, StepOutcome> layerOutcomes,
                                     Set<NodeId> failedNodes,
                                     Function<NodeId, Set<NodeId>> failurePropagationNeighbors,
                                     Map<NodeType, Semaphore> semaphores) {
        NodeId nodeId = step.node().id();

        if (hasFailedNeighbor(nodeId, failedNodes, failurePropagationNeighbors)) {
            layerOutcomes.putIfAbsent(nodeId, new StepOutcome.Failed("skipped: dependency failed"));
            failedNodes.add(nodeId);
            return;
        }

        Semaphore semaphore = semaphores.get(step.node().type());
        try {
            if (semaphore != null) {
                semaphore.acquire();
            }
            try {
                StepOutcome outcome = nodeStepExecutor.execute(
                        step.node(), step.action(), graph, tenancyId);
                layerOutcomes.putIfAbsent(nodeId, outcome);
                if (isFailed(outcome)) {
                    failedNodes.add(nodeId);
                }
            } finally {
                if (semaphore != null) {
                    semaphore.release();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            layerOutcomes.putIfAbsent(nodeId, new StepOutcome.Failed("interrupted"));
            failedNodes.add(nodeId);
        }
    }

    private boolean hasFailedNeighbor(NodeId nodeId, Set<NodeId> failedNodes,
                                      Function<NodeId, Set<NodeId>> neighbors) {
        for (NodeId neighbor : neighbors.apply(nodeId)) {
            if (failedNodes.contains(neighbor)) return true;
        }
        return false;
    }

    private Map<NodeType, Semaphore> buildSemaphores(List<OrderedStep> layer) {
        Map<NodeType, Semaphore> semaphores = new LinkedHashMap<>();
        for (OrderedStep step : layer) {
            NodeType type = step.node().type();
            if (!semaphores.containsKey(type)) {
                OptionalInt max = router.maxConcurrencyFor(type);
                if (max.isPresent()) {
                    semaphores.put(type, new Semaphore(max.getAsInt()));
                }
            }
        }
        return semaphores;
    }

    private static boolean isFailed(StepOutcome outcome) {
        return outcome instanceof StepOutcome.Failed || outcome instanceof StepOutcome.Rejected;
    }
}
