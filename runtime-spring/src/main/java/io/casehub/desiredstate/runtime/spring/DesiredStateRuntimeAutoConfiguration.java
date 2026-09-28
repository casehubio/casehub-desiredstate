package io.casehub.desiredstate.runtime.spring;

import io.casehub.desiredstate.api.ActualStateAdapterRouter;
import io.casehub.desiredstate.api.ConfigurationAdapter;
import io.casehub.desiredstate.api.ConfigurationRetriever;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
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
import io.casehub.desiredstate.runtime.CbrFaultPolicy;
import io.casehub.desiredstate.runtime.CbrProposalTracker;
import io.casehub.desiredstate.runtime.CbrSituationRecompiler;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.DefaultFaultCountStore;
import io.casehub.desiredstate.runtime.DefaultReconciliationStateStore;
import io.casehub.desiredstate.runtime.FaultCountEvictionListener;
import io.casehub.desiredstate.runtime.FaultPolicyEngine;
import io.casehub.desiredstate.runtime.ReconciliationLoop;
import io.casehub.desiredstate.runtime.SimpleTransitionExecutor;
import io.casehub.desiredstate.runtime.SituationRecompilerEngine;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.cloudevents.CloudEvent;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.function.Consumer;

@AutoConfiguration
@ConditionalOnClass(ReconciliationLoop.class)
public class DesiredStateRuntimeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TransitionPlanner transitionPlanner() {
        return new TransitionPlanner();
    }

    @Bean
    @ConditionalOnMissingBean
    public FaultPolicyEngine faultPolicyEngine(List<FaultPolicy> policies) {
        return new FaultPolicyEngine(policies);
    }

    @Bean
    @ConditionalOnMissingBean
    public SituationRecompilerEngine situationRecompilerEngine(
            List<SituationRecompiler> recompilers) {
        return new SituationRecompilerEngine(recompilers);
    }

    @Bean
    @ConditionalOnMissingBean
    public CbrProposalTracker cbrProposalTracker() {
        return new CbrProposalTracker();
    }

    @Bean
    @ConditionalOnMissingBean
    public CbrFaultPolicy cbrFaultPolicy(
            ConfigurationRetriever retriever,
            ConfigurationAdapter adapter,
            PreferenceProvider preferenceProvider,
            CbrProposalTracker tracker) {
        return new CbrFaultPolicy(retriever, adapter, preferenceProvider, tracker);
    }

    @Bean
    @ConditionalOnMissingBean
    public CbrSituationRecompiler cbrSituationRecompiler(
            ConfigurationRetriever retriever,
            ConfigurationAdapter adapter,
            PreferenceProvider preferenceProvider,
            CbrProposalTracker tracker) {
        return new CbrSituationRecompiler(retriever, adapter, preferenceProvider, tracker);
    }

    @Bean
    @ConditionalOnMissingBean
    public FaultCountEvictionListener faultCountEvictionListener(
            FaultCountStore store) {
        return new FaultCountEvictionListener(store);
    }

    @Bean
    @ConditionalOnMissingBean(TransitionExecutor.class)
    public SimpleTransitionExecutor simpleTransitionExecutor(
            NodeProvisionerRouter router,
            HumanNodeHandler humanNodeHandler,
            PendingApprovalHandler pendingApprovalHandler,
            LifecycleStepExecutor lifecycleStepExecutor) {
        return new SimpleTransitionExecutor(router, humanNodeHandler,
                pendingApprovalHandler, lifecycleStepExecutor);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReconciliationLoop reconciliationLoop(
            TransitionPlanner planner,
            TransitionExecutor executor,
            ActualStateAdapterRouter actualStateRouter,
            FaultPolicyEngine faultPolicyEngine,
            MergedEventSource mergedEventSource,
            NodeProvisionerRouter router,
            ApplicationEventPublisher eventPublisher,
            List<GlobalReconciliationListener> listeners,
            CbrProposalTracker cbrTracker,
            ReconciliationStateStore stateStore) {
        Consumer<CloudEvent> cloudEventSink = event -> eventPublisher.publishEvent(event);
        return new ReconciliationLoop(planner, executor, actualStateRouter,
                faultPolicyEngine, mergedEventSource, router,
                ReconciliationLoop.DEFAULT_DEBOUNCE, null,
                cloudEventSink, cbrTracker, listeners, stateStore);
    }

    @Bean
    @ConditionalOnMissingBean(FaultCountStore.class)
    public DefaultFaultCountStore defaultFaultCountStore() {
        return new DefaultFaultCountStore();
    }

    @Bean
    @ConditionalOnMissingBean(ReconciliationStateStore.class)
    public DefaultReconciliationStateStore defaultReconciliationStateStore() {
        return new DefaultReconciliationStateStore();
    }

    @Bean
    @ConditionalOnMissingBean(DesiredStateGraphFactory.class)
    public DefaultDesiredStateGraphFactory defaultDesiredStateGraphFactory() {
        return new DefaultDesiredStateGraphFactory();
    }
}
