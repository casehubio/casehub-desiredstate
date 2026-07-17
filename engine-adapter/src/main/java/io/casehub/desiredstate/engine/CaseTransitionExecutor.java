package io.casehub.desiredstate.engine;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.desiredstate.api.ApprovalCheckResult;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.PendingApprovalHandler;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionExecutor;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.api.TransitionResult;
import io.casehub.engine.flow.FlowWorkerFunction;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.serverlessworkflow.api.types.Workflow;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Engine-backed transition executor that translates a {@link TransitionPlan} into a
 * casehub-engine {@link CaseDefinition} with Worker(Workflow) phases for prune and grow,
 * then starts it via {@link CaseHubRuntime}.
 * <p>
 * Displaces {@code SimpleTransitionExecutor} (marked {@code @DefaultBean}) by classpath
 * presence when the engine-adapter module is on the classpath.
 * <p>
 * <b>V1 simplification:</b> Case completion is reported optimistically. Proper observation
 * of case completion events (waiting for the case to finish, collecting per-node outcomes)
 * is a follow-up. The returned {@link TransitionResult} marks all steps as
 * {@link StepOutcome.Succeeded} immediately after the case is started.
 */
@ApplicationScoped
public class CaseTransitionExecutor implements TransitionExecutor {

    private static final Logger LOG = Logger.getLogger(CaseTransitionExecutor.class);

    static final String NAMESPACE = "io.casehub.desiredstate";
    static final String CASE_VERSION = "1.0.0";

    private final TransitionWorkflowGenerator workflowGenerator;
    private final CaseHubRuntime caseHubRuntime;
    private final PendingApprovalHandler pendingApprovalHandler;
    private final DesiredStateExecutionRegistry executionRegistry;

    @Inject
    public CaseTransitionExecutor(TransitionWorkflowGenerator workflowGenerator,
                                  CaseHubRuntime caseHubRuntime,
                                  PendingApprovalHandler pendingApprovalHandler,
                                  DesiredStateExecutionRegistry executionRegistry) {
        this.workflowGenerator = workflowGenerator;
        this.caseHubRuntime = caseHubRuntime;
        this.pendingApprovalHandler = pendingApprovalHandler;
        this.executionRegistry = executionRegistry;
    }

    @Override
    public Uni<TransitionResult> execute(TransitionPlan plan, String tenancyId) {
        if (plan.isEmpty()) {
            return Uni.createFrom().item(new TransitionResult(Map.of()));
        }

        Map<NodeId, StepOutcome> preFilteredOutcomes = new LinkedHashMap<>();
        List<OrderedStep> runnableRemovals = new ArrayList<>();
        List<OrderedStep> runnableAdditions = new ArrayList<>();

        for (OrderedStep step : plan.removals()) {
            StepOutcome filtered = checkApproval(step, tenancyId);
            if (filtered != null) {
                preFilteredOutcomes.put(step.node().id(), filtered);
            } else {
                runnableRemovals.add(step);
            }
        }

        for (OrderedStep step : plan.additions()) {
            StepOutcome filtered = checkApproval(step, tenancyId);
            if (filtered != null) {
                preFilteredOutcomes.put(step.node().id(), filtered);
            } else {
                runnableAdditions.add(step);
            }
        }

        // If everything was filtered, return immediately — no case needed
        if (runnableRemovals.isEmpty() && runnableAdditions.isEmpty()) {
            return Uni.createFrom().item(new TransitionResult(preFilteredOutcomes));
        }

        TransitionPlan runnablePlan = new TransitionPlan(
            runnableRemovals, runnableAdditions, plan.before(), plan.after());

        executionRegistry.getActiveCaseId(tenancyId).ifPresent(id -> {
            try {
                caseHubRuntime.cancelCase(id);
            } catch (Exception e) {
                LOG.warnf("Failed to cancel previous case %s for tenancy %s — proceeding", id, tenancyId);
            }
        });

        String executionId = UUID.randomUUID().toString();
        executionRegistry.register(executionId, plan.after(), tenancyId);

        return Uni.createFrom().completionStage(() -> {
            CaseDefinition caseDefinition = buildCaseDefinition(runnablePlan, executionId);

            Map<String, Object> inputData = Map.of(
                "removals", runnablePlan.removals().size(),
                "additions", runnablePlan.additions().size(),
                "graphVersion", runnablePlan.after().version()
            );

            return caseHubRuntime.startCase(caseDefinition, inputData);
        }).onFailure().invoke(() -> executionRegistry.remove(executionId))
          .map(caseId -> {
            LOG.infof("Started desired-state transition case %s (removals=%d, additions=%d)",
                caseId, runnableRemovals.size(), runnableAdditions.size());

            executionRegistry.remove(executionId);
            executionRegistry.setActiveCaseId(tenancyId, caseId);

            Map<NodeId, StepOutcome> allOutcomes = new LinkedHashMap<>(preFilteredOutcomes);
            allOutcomes.putAll(buildOptimisticResult(runnablePlan, caseId).outcomes());
            return new TransitionResult(allOutcomes);
        });
    }

    private StepOutcome checkApproval(OrderedStep step, String tenancyId) {
        if (step.node().requiresHuman(step.action())) {
            return null; // human nodes handled separately in buildCaseDefinition
        }
        ApprovalCheckResult check = pendingApprovalHandler.check(
            step.node(), step.action(), tenancyId);
        return switch (check) {
            case ApprovalCheckResult.Pending p ->
                new StepOutcome.Skipped("pending approval: " + p.planReference());
            case ApprovalCheckResult.Rejected r -> {
                pendingApprovalHandler.acknowledgeRejection(
                    step.node(), step.action(), tenancyId);
                yield new StepOutcome.Rejected("approval rejected: " + r.reason());
            }
            case ApprovalCheckResult.Approved ignored -> null; // include in case
            case ApprovalCheckResult.None ignored -> null; // include in case
        };
    }

    CaseDefinition buildCaseDefinition(TransitionPlan plan, String executionId) {
        List<Worker>  workers  = new ArrayList<>(2);
        List<Binding> bindings = new ArrayList<>(2);

        Capability dispatchCapability = Capability.builder()
                                                  .name("desiredstate-dispatch")
                                                  .inputSchema("{}")
                                                  .outputSchema("{}")
                                                  .description("Dispatches desired-state node provision/deprovision actions")
                                                  .build();

        List<OrderedStep> automatedRemovals = new ArrayList<>();
        List<OrderedStep> humanRemovals     = new ArrayList<>();
        for (OrderedStep step : plan.removals()) {
            if (step.node().requiresHuman(step.action())) {
                humanRemovals.add(step);
            } else {
                automatedRemovals.add(step);
            }
        }

        if (!automatedRemovals.isEmpty()) {
            Workflow pruneWorkflow = workflowGenerator.generate(
                    automatedRemovals, NAMESPACE, "prune-phase", CASE_VERSION, executionId
                                                               );

            Worker pruneWorker = Worker.builder()
                                       .name("prune")
                                       .capabilityName(dispatchCapability.name())
                                       .function(new FlowWorkerFunction(pruneWorkflow))
                                       .description("Removes nodes no longer in the desired state (leaves before roots)")
                                       .build();

            workers.add(pruneWorker);
            bindings.add(buildBinding("prune-binding", dispatchCapability));
        }

        List<OrderedStep> automatedAdditions = new ArrayList<>();
        List<OrderedStep> humanAdditions     = new ArrayList<>();
        for (OrderedStep step : plan.additions()) {
            if (step.node().requiresHuman(step.action())) {
                humanAdditions.add(step);
            } else {
                automatedAdditions.add(step);
            }
        }

        if (!automatedAdditions.isEmpty()) {
            Workflow growWorkflow = workflowGenerator.generate(
                    automatedAdditions, NAMESPACE, "grow-phase", CASE_VERSION, executionId
                                                              );

            Worker growWorker = Worker.builder()
                                      .name("grow")
                                      .capabilityName(dispatchCapability.name())
                                      .function(new FlowWorkerFunction(growWorkflow))
                                      .description("Provisions new nodes in the desired state (roots before leaves)")
                                      .build();

            workers.add(growWorker);
            if (automatedRemovals.isEmpty()) {
                bindings.add(buildBinding("grow-binding", dispatchCapability));
            }
        }

        for (OrderedStep step : humanRemovals) {
            HumanTaskTarget humanTask = HumanTaskTarget.inline()
                                                       .title("Review removal: " + step.node().id().value())
                                                       .build();

            bindings.add(Binding.builder()
                                .name("human-deprovision-" + step.node().id().value())
                                .humanTask(humanTask)
                                .on(new ContextChangeTrigger("."))
                                .build());
        }

        for (OrderedStep step : humanAdditions) {
            HumanTaskTarget humanTask = HumanTaskTarget.inline()
                                                       .title("Review: " + step.node().id().value())
                                                       .build();

            bindings.add(Binding.builder()
                                .name("human-provision-" + step.node().id().value())
                                .humanTask(humanTask)
                                .on(new ContextChangeTrigger("."))
                                .build());
        }

        return CaseDefinition.builder()
                             .namespace(NAMESPACE)
                             .name("desired-state-transition")
                             .version(CASE_VERSION)
                             .title("Desired State Transition")
                             .summary("Automated desired-state transition: " + automatedRemovals.size()
                                      + " removals, " + automatedAdditions.size() + " additions, "
                                      + humanRemovals.size() + " human removals, "
                                      + humanAdditions.size() + " human additions")
                             .workers(workers)
                             .bindings(bindings)
                             .build();
    }

    private Binding buildBinding(String name, Capability capability) {
        return Binding.builder()
            .name(name)
            .capability(capability)
            .on(new ContextChangeTrigger("."))
            .build();
    }

    private TransitionResult buildOptimisticResult(TransitionPlan plan, UUID caseId) {
        Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();

        for (OrderedStep step : plan.removals()) {
            if (step.node().requiresHuman(step.action())) {
                outcomes.put(step.node().id(), new StepOutcome.Skipped("routed to human task binding"));
            } else {
                outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            }
        }
        for (OrderedStep step : plan.additions()) {
            if (step.node().requiresHuman(step.action())) {
                outcomes.put(step.node().id(), new StepOutcome.Skipped("routed to human task binding"));
            } else {
                outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            }
        }

        return new TransitionResult(outcomes);
    }
}
