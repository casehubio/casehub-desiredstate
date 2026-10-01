package io.casehub.desiredstate.runtime.spring;

import io.casehub.desiredstate.api.ActualStateAdapterRouter;
import io.casehub.desiredstate.api.ConfigurationAdapter;
import io.casehub.desiredstate.api.ConfigurationRetriever;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.DriftPolicy;
import io.casehub.desiredstate.api.ExemptionStore;
import io.casehub.desiredstate.api.FaultCountStore;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.InMemoryExemptionStore;
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
import io.casehub.desiredstate.runtime.CbrFaultPolicy;
import io.casehub.desiredstate.runtime.CbrProposalTracker;
import io.casehub.desiredstate.runtime.CbrSituationRecompiler;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.DefaultFaultCountStore;
import io.casehub.desiredstate.runtime.DefaultReconciliationStateStore;
import io.casehub.desiredstate.runtime.DriftPolicyEngine;
import io.casehub.desiredstate.runtime.ExemptionEvictionListener;
import io.casehub.desiredstate.runtime.FaultCountEvictionListener;
import io.casehub.desiredstate.runtime.FaultPolicyEngine;
import io.casehub.desiredstate.runtime.NodeStepExecutor;
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
    @ConditionalOnMissingBean
    public DriftPolicyEngine driftPolicyEngine(List<DriftPolicy> policies) {
        policies.sort(org.springframework.core.annotation.AnnotationAwareOrderComparator.INSTANCE);
        return new DriftPolicyEngine(policies);
    }

    @Bean
    @ConditionalOnMissingBean(ExemptionStore.class)
    public ExemptionStore exemptionStore() {
        return new InMemoryExemptionStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ExemptionEvictionListener exemptionEvictionListener(ExemptionStore store) {
        return new ExemptionEvictionListener(store);
    }

    @Bean
    @ConditionalOnMissingBean
    public NodeStepExecutor nodeStepExecutor(
            NodeProvisionerRouter router,
            HumanNodeHandler humanNodeHandler,
            PendingApprovalHandler pendingApprovalHandler,
            LifecycleStepExecutor lifecycleStepExecutor) {
        return new NodeStepExecutor(router, humanNodeHandler,
                                    pendingApprovalHandler, lifecycleStepExecutor);
    }

    @Bean
    @ConditionalOnMissingBean(TransitionExecutor.class)
    public TransitionExecutor transitionExecutor(NodeStepExecutor nodeStepExecutor,
                                                 NodeProvisionerRouter router,
                                                 PreferenceProvider preferenceProvider) {
        io.casehub.platform.api.preferences.Preferences prefs = preferenceProvider.resolve(
                io.casehub.platform.api.preferences.SettingsScope.root(
                        io.casehub.platform.api.identity.TenancyConstants.PLATFORM_TENANT_ID));
        io.casehub.platform.api.preferences.BooleanPreference parallel =
                prefs.get(io.casehub.desiredstate.runtime.DesiredStatePreferenceKeys.PARALLEL_EXECUTION);
        if (parallel != null && parallel.value()) {
            return new io.casehub.desiredstate.runtime.ParallelTransitionExecutor(
                    nodeStepExecutor, router, java.time.Duration.ofMinutes(5));
        }
        return new SimpleTransitionExecutor(nodeStepExecutor);
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
            ReconciliationStateStore stateStore,
            DriftPolicyEngine driftPolicyEngine,
            ExemptionStore exemptionStore) {
        Consumer<CloudEvent> cloudEventSink = event -> eventPublisher.publishEvent(event);
        return new ReconciliationLoop(planner, executor, actualStateRouter,
                                      faultPolicyEngine, mergedEventSource, router,
                                      ReconciliationLoop.DEFAULT_DEBOUNCE, null,
                                      cloudEventSink, cbrTracker, listeners, stateStore,
                                      ReconciliationCompletedData.NODE_OUTCOMES_THRESHOLD,
                                      driftPolicyEngine, exemptionStore);
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

    @Bean
    @ConditionalOnMissingBean
    public io.casehub.desiredstate.runtime.ReconciliationEventEmitter reconciliationEventEmitter() {
        return new io.casehub.desiredstate.runtime.ReconciliationEventEmitter();
    }

    @Bean
    @ConditionalOnMissingBean(io.casehub.desiredstate.runtime.TransitionActionHandler.class)
    public io.casehub.desiredstate.runtime.TransitionActionHandler transitionActionHandler(
            io.casehub.desiredstate.runtime.ReconciliationEventEmitter emitter,
            ApplicationEventPublisher eventPublisher) {
        return (action, nodeId, state, tenancyId) -> {
            if (action instanceof io.casehub.desiredstate.api.TransitionAction.EmitEvent emit) {
                var data = new io.casehub.desiredstate.api.LifecycleStateEnteredData(
                        tenancyId, nodeId.value(), null, state.name(), null, emit.eventType());
                CloudEvent event = emitter.lifecycleStateEntered(data);
                eventPublisher.publishEvent(event);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(NodeProvisionerRouter.class)
    public io.casehub.desiredstate.runtime.DefaultNodeProvisionerRouter nodeProvisionerRouter(
            List<io.casehub.desiredstate.api.NodeProvisioner> provisioners,
            List<io.casehub.desiredstate.api.NodeLifecycleDefinition> lifecycles,
            io.casehub.desiredstate.runtime.TransitionActionHandler actionHandler) {
        java.util.Map<io.casehub.desiredstate.api.NodeType, io.casehub.desiredstate.api.NodeLifecycleDefinition> lifecycleMap = new java.util.HashMap<>();
        for (var lifecycle : lifecycles) {
            lifecycleMap.put(lifecycle.nodeType(), lifecycle);
        }
        java.util.List<io.casehub.desiredstate.api.NodeProvisioner> wrapped = new java.util.ArrayList<>(provisioners.size());
        for (var prov : provisioners) {
            io.casehub.desiredstate.api.NodeLifecycleDefinition matched = null;
            for (var type : prov.handledTypes()) {
                matched = lifecycleMap.get(type);
                if (matched != null) {break;}
            }
            if (matched != null) {
                wrapped.add(new io.casehub.desiredstate.runtime.StatefulNodeProvisioner(prov, matched, actionHandler));
            } else {
                wrapped.add(prov);
            }
        }
        return new io.casehub.desiredstate.runtime.DefaultNodeProvisionerRouter(wrapped);
    }

}
