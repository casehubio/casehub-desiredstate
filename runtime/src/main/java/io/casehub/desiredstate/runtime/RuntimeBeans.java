package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualStateAdapterRouter;
import io.casehub.desiredstate.api.ConfigurationAdapter;
import io.casehub.desiredstate.api.ConfigurationRetriever;
import io.casehub.desiredstate.api.DriftPolicy;
import io.casehub.desiredstate.api.ExemptionStore;
import io.casehub.desiredstate.api.FaultCountStore;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.GlobalReconciliationListener;
import io.casehub.desiredstate.api.HumanNodeHandler;
import io.casehub.desiredstate.api.LifecycleStepExecutor;
import io.casehub.desiredstate.api.MergedEventSource;
import io.casehub.desiredstate.api.NodeProvisionerRouter;
import io.casehub.desiredstate.api.PendingApprovalHandler;
import io.casehub.desiredstate.api.ReconciliationCompletedData;
import io.casehub.desiredstate.api.ReconciliationStateStore;
import io.casehub.desiredstate.api.SituationRecompiler;
import io.casehub.desiredstate.api.TransitionExecutor;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.cloudevents.CloudEvent;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class RuntimeBeans {

    @Produces
    @ApplicationScoped
    public TransitionPlanner transitionPlanner() {
        return new TransitionPlanner();
    }

    @Produces
    @ApplicationScoped
    public FaultPolicyEngine faultPolicyEngine(
            Instance<FaultPolicy> policies,
            FaultCountStore store) {
        return new FaultPolicyEngine(policies.stream().toList());
    }

    @Produces
    @ApplicationScoped
    public SituationRecompilerEngine situationRecompilerEngine(
            Instance<SituationRecompiler> recompilers) {
        return new SituationRecompilerEngine(recompilers.stream().toList());
    }

    @Produces
    @ApplicationScoped
    public CbrFaultPolicy cbrFaultPolicy(
            ConfigurationRetriever retriever,
            ConfigurationAdapter adapter,
            PreferenceProvider preferenceProvider,
            CbrProposalTracker tracker) {
        return new CbrFaultPolicy(retriever, adapter, preferenceProvider, tracker);
    }

    @Produces
    @ApplicationScoped
    public CbrSituationRecompiler cbrSituationRecompiler(
            ConfigurationRetriever retriever,
            ConfigurationAdapter adapter,
            PreferenceProvider preferenceProvider,
            CbrProposalTracker tracker) {
        return new CbrSituationRecompiler(retriever, adapter, preferenceProvider, tracker);
    }

    @Produces
    @ApplicationScoped
    public CbrProposalTracker cbrProposalTracker() {
        return new CbrProposalTracker();
    }

    @Produces
    @ApplicationScoped
    public FaultCountEvictionListener faultCountEvictionListener(
            FaultCountStore store) {
        return new FaultCountEvictionListener(store);
    }

    @Produces
    @ApplicationScoped
    public DriftPolicyEngine driftPolicyEngine(Instance<DriftPolicy> policies) {
        return new DriftPolicyEngine(policies.stream()
                                             .sorted(java.util.Comparator.comparingInt(p -> {
                                                 var priority = p.getClass().getAnnotation(jakarta.annotation.Priority.class);
                                                 return priority != null ? -priority.value() : 0;
                                             }))
                                             .toList());
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public DefaultExemptionStore defaultExemptionStore() {
        return new DefaultExemptionStore();
    }

    @Produces
    @ApplicationScoped
    public ExemptionEvictionListener exemptionEvictionListener(ExemptionStore store) {
        return new ExemptionEvictionListener(store);
    }


    @Produces
    @DefaultBean
    @ApplicationScoped
    public NodeStepExecutor nodeStepExecutor(
            NodeProvisionerRouter router,
            HumanNodeHandler humanNodeHandler,
            PendingApprovalHandler pendingApprovalHandler,
            LifecycleStepExecutor lifecycleStepExecutor) {
        return new NodeStepExecutor(router, humanNodeHandler,
                                    pendingApprovalHandler, lifecycleStepExecutor);
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public TransitionExecutor transitionExecutor(NodeStepExecutor nodeStepExecutor,
                                                 NodeProvisionerRouter router,
                                                 PreferenceProvider preferenceProvider) {
        io.casehub.platform.api.preferences.Preferences prefs = preferenceProvider.resolve(
                io.casehub.platform.api.preferences.SettingsScope.root(
                        io.casehub.platform.api.identity.TenancyConstants.PLATFORM_TENANT_ID));
        io.casehub.platform.api.preferences.BooleanPreference parallel =
                prefs.get(DesiredStatePreferenceKeys.PARALLEL_EXECUTION);
        if (parallel != null && parallel.value()) {
            return new ParallelTransitionExecutor(nodeStepExecutor, router, java.time.Duration.ofMinutes(5));
        }
        return new SimpleTransitionExecutor(nodeStepExecutor);
    }


    @Produces
    @ApplicationScoped
    public PlanApprovalGate planApprovalGate(io.casehub.desiredstate.api.PlanApprovalPolicy policy,
                                             io.casehub.desiredstate.api.PlanApprovalHandler handler) {
        return new PlanApprovalGate(policy, handler);
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public ReconciliationLoop reconciliationLoop(
            TransitionPlanner planner,
            TransitionExecutor executor,
            ActualStateAdapterRouter actualStateRouter,
            FaultPolicyEngine faultPolicyEngine,
            MergedEventSource mergedEventSource,
            NodeProvisionerRouter router,
            Event<CloudEvent> cloudEventSink,
            Instance<GlobalReconciliationListener> listeners,
            CbrProposalTracker cbrTracker,
            ReconciliationStateStore stateStore,
            DriftPolicyEngine driftPolicyEngine,
            ExemptionStore exemptionStore,
            PlanApprovalGate approvalGate) {
        return new ReconciliationLoop(planner, executor, actualStateRouter,
                                      faultPolicyEngine, mergedEventSource, router,
                                      ReconciliationLoop.DEFAULT_DEBOUNCE, null,
                                      cloudEventSink::fire, cbrTracker,
                                      listeners.stream().toList(), stateStore,
                                      ReconciliationCompletedData.NODE_OUTCOMES_THRESHOLD,
                                      driftPolicyEngine, exemptionStore, approvalGate);
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public DefaultFaultCountStore defaultFaultCountStore() {
        return new DefaultFaultCountStore();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public DefaultReconciliationStateStore defaultReconciliationStateStore() {
        return new DefaultReconciliationStateStore();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public DefaultDesiredStateGraphFactory defaultDesiredStateGraphFactory() {
        return new DefaultDesiredStateGraphFactory();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public ReconciliationEventEmitter reconciliationEventEmitter() {
        return new ReconciliationEventEmitter();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public NoOpHumanNodeHandler noOpHumanNodeHandler() {
        return new NoOpHumanNodeHandler();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public NoOpPendingApprovalHandler noOpPendingApprovalHandler() {
        return new NoOpPendingApprovalHandler();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public NoOpConfigurationRetriever noOpConfigurationRetriever() {
        return new NoOpConfigurationRetriever();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public NoOpConfigurationAdapter noOpConfigurationAdapter() {
        return new NoOpConfigurationAdapter();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public LoggingNotificationSink loggingNotificationSink() {
        return new LoggingNotificationSink();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public DefaultLifecycleStepExecutor defaultLifecycleStepExecutor(
            io.casehub.desiredstate.api.NotificationSink notificationSink) {
        return new DefaultLifecycleStepExecutor(notificationSink);
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public NoOpPlanApprovalPolicy noOpPlanApprovalPolicy() {
        return new NoOpPlanApprovalPolicy();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public NoOpPlanApprovalHandler noOpPlanApprovalHandler() {
        return new NoOpPlanApprovalHandler();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public NoOpSituationSource noOpSituationSource() {
        return new NoOpSituationSource();
    }

    @Produces
    @DefaultBean
    @ApplicationScoped
    public LifecycleManager lifecycleManager(ReconciliationLoop loop) {
        return new LifecycleManager(loop);
    }

    @Produces
    @ApplicationScoped
    public SituationRecompilerDispatchCore situationRecompilerDispatchCore(
            SituationRecompilerEngine engine,
            LifecycleManager lifecycleManager,
            ReconciliationLoop reconciliationLoop,
            io.casehub.desiredstate.api.ActualStateAdapterRouter actualStateRouter,
            io.casehub.desiredstate.api.DesiredStateGraphFactory graphFactory,
            io.casehub.ras.api.SituationSource situationSource) {
        return new SituationRecompilerDispatchCore(engine, lifecycleManager,
                                                   reconciliationLoop, actualStateRouter, graphFactory, situationSource);
    }


}
