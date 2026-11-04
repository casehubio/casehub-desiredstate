package io.casehub.desiredstate.engine;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.JudgmentTarget;
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
    public TransitionResult execute(TransitionPlan plan, String tenancyId) {
        if (plan.isEmpty()) {
            return new TransitionResult(Map.of());
        }

        Map<NodeId, StepOutcome> preFilteredOutcomes = new LinkedHashMap<>();
        List<OrderedStep>        runnableRemovals    = new ArrayList<>();
        List<OrderedStep>        runnableSuspensions = new ArrayList<>();
        List<OrderedStep>        runnableResumptions = new ArrayList<>();
        List<OrderedStep>        runnableAdditions   = new ArrayList<>();

        for (OrderedStep step : plan.flatRemovals()) {
            StepOutcome filtered = checkApproval(step, tenancyId);
            if (filtered != null) {
                preFilteredOutcomes.put(step.node().id(), filtered);
            } else {
                runnableRemovals.add(step);
            }
        }

        for (OrderedStep step : plan.flatSuspensions()) {
            StepOutcome filtered = checkApproval(step, tenancyId);
            if (filtered != null) {
                preFilteredOutcomes.put(step.node().id(), filtered);
            } else {
                runnableSuspensions.add(step);
            }
        }

        for (OrderedStep step : plan.flatResumptions()) {
            StepOutcome filtered = checkApproval(step, tenancyId);
            if (filtered != null) {
                preFilteredOutcomes.put(step.node().id(), filtered);
            } else {
                runnableResumptions.add(step);
            }
        }

        for (OrderedStep step : plan.flatAdditions()) {
            StepOutcome filtered = checkApproval(step, tenancyId);
            if (filtered != null) {
                preFilteredOutcomes.put(step.node().id(), filtered);
            } else {
                runnableAdditions.add(step);
            }
        }

        if (runnableRemovals.isEmpty() && runnableSuspensions.isEmpty()
            && runnableResumptions.isEmpty() && runnableAdditions.isEmpty()) {
            return new TransitionResult(preFilteredOutcomes);
        }

        TransitionPlan runnablePlan = TransitionPlan.ofFlat(
                runnableRemovals, runnableSuspensions, runnableResumptions,
                runnableAdditions, plan.before(), plan.after());

        executionRegistry.getActiveCaseId(tenancyId).ifPresent(id -> {
            try {
                caseHubRuntime.cancelCase(id);
            } catch (Exception e) {
                LOG.warnf("Failed to cancel previous case %s for tenancy %s — proceeding", id, tenancyId);
            }
        });

        String executionId = UUID.randomUUID().toString();
        executionRegistry.register(executionId, plan.after(), tenancyId);

        CaseDefinition caseDefinition = buildCaseDefinition(runnablePlan, executionId);

        Map<String, Object> inputData = Map.of(
                "removals", runnablePlan.flatRemovals().size(),
                "suspensions", runnablePlan.flatSuspensions().size(),
                "resumptions", runnablePlan.flatResumptions().size(),
                "additions", runnablePlan.flatAdditions().size(),
                "graphVersion", runnablePlan.after().version()
                                              );

        UUID caseId;
        try {
            caseId = caseHubRuntime.startCase(caseDefinition, inputData);
        } catch (Exception e) {
            executionRegistry.remove(executionId);
            throw e;
        }

        LOG.infof("Started desired-state transition case %s (removals=%d, suspensions=%d, resumptions=%d, additions=%d)",
                  caseId, runnableRemovals.size(), runnableSuspensions.size(),
                  runnableResumptions.size(), runnableAdditions.size());

        executionRegistry.remove(executionId);
        executionRegistry.setActiveCaseId(tenancyId, caseId);

        Map<NodeId, StepOutcome> allOutcomes = new LinkedHashMap<>(preFilteredOutcomes);
        allOutcomes.putAll(buildOptimisticResult(runnablePlan, caseId).outcomes());
        return new TransitionResult(allOutcomes);
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
        List<Worker>  workers  = new ArrayList<>(4);
        List<Binding> bindings = new ArrayList<>(4);

        Capability dispatchCapability = Capability.builder()
                                                  .name("desiredstate-dispatch")
                                                  .inputSchema("{}")
                                                  .outputSchema("{}")
                                                  .description("Dispatches desired-state node lifecycle actions")
                                                  .build();

        List<OrderedStep> automatedRemovals = new ArrayList<>();
        List<OrderedStep> humanRemovals     = new ArrayList<>();
        for (OrderedStep step : plan.flatRemovals()) {
            if (step.node().requiresHuman(step.action())) {
                humanRemovals.add(step);
            } else {
                automatedRemovals.add(step);
            }
        }

        if (!automatedRemovals.isEmpty()) {
            Workflow pruneWorkflow = workflowGenerator.generate(
                    automatedRemovals, NAMESPACE, "prune-phase", CASE_VERSION, executionId);
            workers.add(Worker.builder()
                              .name("prune")
                              .capabilityName(dispatchCapability.name())
                              .function(new FlowWorkerFunction(pruneWorkflow))
                              .description("Removes nodes no longer in the desired state (leaves before roots)")
                              .build());
            bindings.add(buildBinding("prune-binding", dispatchCapability));
        }

        List<OrderedStep> automatedSuspensions = new ArrayList<>();
        List<OrderedStep> humanSuspensions     = new ArrayList<>();
        for (OrderedStep step : plan.flatSuspensions()) {
            if (step.node().requiresHuman(step.action())) {
                humanSuspensions.add(step);
            } else {
                automatedSuspensions.add(step);
            }
        }

        if (!automatedSuspensions.isEmpty()) {
            Workflow freezeWorkflow = workflowGenerator.generate(
                    automatedSuspensions, NAMESPACE, "freeze-phase", CASE_VERSION, executionId);
            workers.add(Worker.builder()
                              .name("freeze")
                              .capabilityName(dispatchCapability.name())
                              .function(new FlowWorkerFunction(freezeWorkflow))
                              .description("Suspends nodes transitioning to hibernation (leaves before roots)")
                              .build());
            if (automatedRemovals.isEmpty()) {
                bindings.add(buildBinding("freeze-binding", dispatchCapability));
            }
        }

        List<OrderedStep> automatedResumptions = new ArrayList<>();
        List<OrderedStep> humanResumptions     = new ArrayList<>();
        for (OrderedStep step : plan.flatResumptions()) {
            if (step.node().requiresHuman(step.action())) {
                humanResumptions.add(step);
            } else {
                automatedResumptions.add(step);
            }
        }

        if (!automatedResumptions.isEmpty()) {
            Workflow thawWorkflow = workflowGenerator.generate(
                    automatedResumptions, NAMESPACE, "thaw-phase", CASE_VERSION, executionId);
            workers.add(Worker.builder()
                              .name("thaw")
                              .capabilityName(dispatchCapability.name())
                              .function(new FlowWorkerFunction(thawWorkflow))
                              .description("Resumes nodes returning from hibernation (roots before leaves)")
                              .build());
            if (automatedRemovals.isEmpty() && automatedSuspensions.isEmpty()) {
                bindings.add(buildBinding("thaw-binding", dispatchCapability));
            }
        }

        List<OrderedStep> automatedAdditions = new ArrayList<>();
        List<OrderedStep> humanAdditions     = new ArrayList<>();
        for (OrderedStep step : plan.flatAdditions()) {
            if (step.node().requiresHuman(step.action())) {
                humanAdditions.add(step);
            } else {
                automatedAdditions.add(step);
            }
        }

        if (!automatedAdditions.isEmpty()) {
            Workflow growWorkflow = workflowGenerator.generate(
                    automatedAdditions, NAMESPACE, "grow-phase", CASE_VERSION, executionId);
            workers.add(Worker.builder()
                              .name("grow")
                              .capabilityName(dispatchCapability.name())
                              .function(new FlowWorkerFunction(growWorkflow))
                              .description("Provisions new nodes in the desired state (roots before leaves)")
                              .build());
            if (automatedRemovals.isEmpty() && automatedSuspensions.isEmpty() && automatedResumptions.isEmpty()) {
                bindings.add(buildBinding("grow-binding", dispatchCapability));
            }
        }

        for (OrderedStep step : humanRemovals) {
            bindings.add(buildHumanBinding("human-deprovision-" + step.node().id().value(),
                                           "Review removal: " + step.node().id().value()));
        }

        for (OrderedStep step : humanSuspensions) {
            bindings.add(buildHumanBinding("human-suspend-" + step.node().id().value(),
                                           "Review suspend: " + step.node().id().value()));
        }

        for (OrderedStep step : humanResumptions) {
            bindings.add(buildHumanBinding("human-resume-" + step.node().id().value(),
                                           "Review resume: " + step.node().id().value()));
        }

        for (OrderedStep step : humanAdditions) {
            bindings.add(buildHumanBinding("human-provision-" + step.node().id().value(),
                                           "Review: " + step.node().id().value()));
        }

        return CaseDefinition.builder()
                             .namespace(NAMESPACE)
                             .name("desired-state-transition")
                             .version(CASE_VERSION)
                             .title("Desired State Transition")
                             .summary("Automated desired-state transition: "
                                      + automatedRemovals.size() + " removals, "
                                      + automatedSuspensions.size() + " suspensions, "
                                      + automatedResumptions.size() + " resumptions, "
                                      + automatedAdditions.size() + " additions, "
                                      + humanRemovals.size() + " human removals, "
                                      + humanSuspensions.size() + " human suspensions, "
                                      + humanResumptions.size() + " human resumptions, "
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

    private Binding buildHumanBinding(String name, String prompt) {
        JudgmentTarget judgment = JudgmentTarget.builder()
                                                .prompt(prompt)
                                                .title(prompt)
                                                .build();
        return Binding.builder()
                      .name(name)
                      .judgment(judgment)
                      .on(new ContextChangeTrigger("."))
                      .build();
    }


    private TransitionResult buildOptimisticResult(TransitionPlan plan, UUID caseId) {
        Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();

        for (List<OrderedStep> steps : List.of(plan.flatRemovals(), plan.flatSuspensions(),
                                               plan.flatResumptions(), plan.flatAdditions())) {
            for (OrderedStep step : steps) {
                if (step.node().requiresHuman(step.action())) {
                    outcomes.put(step.node().id(), new StepOutcome.Skipped("routed to human task binding"));
                } else {
                    outcomes.put(step.node().id(), new StepOutcome.Succeeded());
                }
            }
        }

        return new TransitionResult(outcomes);
    }
}
