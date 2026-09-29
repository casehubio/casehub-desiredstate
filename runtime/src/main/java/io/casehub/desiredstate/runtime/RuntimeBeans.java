package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualStateAdapterRouter;
import io.casehub.desiredstate.api.ConfigurationAdapter;
import io.casehub.desiredstate.api.ConfigurationRetriever;
import io.casehub.desiredstate.api.FaultCountStore;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.GlobalReconciliationListener;
import io.casehub.desiredstate.api.HumanNodeHandler;
import io.casehub.desiredstate.api.LifecycleStepExecutor;
import io.casehub.desiredstate.api.MergedEventSource;
import io.casehub.desiredstate.api.NodeProvisionerRouter;
import io.casehub.desiredstate.api.PendingApprovalHandler;
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
            ReconciliationStateStore stateStore) {
        return new ReconciliationLoop(planner, executor, actualStateRouter,
                faultPolicyEngine, mergedEventSource, router,
                ReconciliationLoop.DEFAULT_DEBOUNCE, null,
                cloudEventSink::fire, cbrTracker,
                listeners.stream().toList(), stateStore);
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

}
