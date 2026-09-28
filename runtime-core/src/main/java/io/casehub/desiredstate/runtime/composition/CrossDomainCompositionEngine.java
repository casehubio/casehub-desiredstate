package io.casehub.desiredstate.runtime.composition;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.CompletionCondition;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.DomainId;
import io.casehub.desiredstate.api.GlobalReconciliationListener;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.Phase;
import io.casehub.desiredstate.api.SituationRecompiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class CrossDomainCompositionEngine implements GlobalReconciliationListener {

    private static final Logger LOG = Logger.getLogger(CrossDomainCompositionEngine.class.getName());

    private final DesiredStateGraphFactory graphFactory;
    private volatile boolean composed = false;

    private final Map<DomainId, DomainRegistration> domainConfigs = new LinkedHashMap<>();
    private final Map<SituationRecompiler, DomainId> recompilerIndex = new IdentityHashMap<>();
    private List<Map.Entry<SituationRecompiler, DomainId>> sortedRecompilers = List.of();
    private List<SituationRecompiler> sortedCrossDomainRecompilers = List.of();
    private List<DomainId> topologicalOrder = List.of();

    private final ConcurrentHashMap<String, TenantCompositionState> tenantStates = new ConcurrentHashMap<>();
    private final Object recomposeLock = new Object();

    public CrossDomainCompositionEngine(DesiredStateGraphFactory graphFactory) {
        this.graphFactory = graphFactory;
    }

    public void registerDomain(DomainRegistration registration) {
        if (composed) {
            throw new IllegalStateException(
                "Cannot register domain '" + registration.domainId()
                + "' — composition already completed. "
                + "Ensure domain registrar @Priority is below PLATFORM_AFTER + 1000.");
        }
        domainConfigs.put(registration.domainId(), registration);
        for (SituationRecompiler r : registration.situationRecompilers()) {
            recompilerIndex.put(r, registration.domainId());
        }
    }

    public int registrationCount() { return domainConfigs.size(); }
    public boolean isActive() { return composed && registrationCount() > 1; }
    public List<DomainId> topologicalOrder() { return topologicalOrder; }
    DesiredStateGraphFactory graphFactory() { return graphFactory; }
    void markComposed() { this.composed = true; }

    public void compose() {
        validate();
        composed = true;
    }

    public TenantCompositionState initTenantState() {
        Map<DomainId, DomainPhaseState> phases = new LinkedHashMap<>();
        for (DomainId id : topologicalOrder) {
            var reg = domainConfigs.get(id);
            phases.put(id, new DomainPhaseState(reg.compilationResult(), 0));
        }
        return new TenantCompositionState(Map.copyOf(phases));
    }


    public void validate() {
        validateDuplicateProvides();
        validateUnsatisfiedRequires();
        topologicalOrder = computeTopologicalOrder();
        validateNodeIdUniqueness();
        buildSortedRecompilers();
    }

    private void validateDuplicateProvides() {
        Map<NodeType, DomainId> seen = new LinkedHashMap<>();
        for (var entry : domainConfigs.entrySet()) {
            for (NodeType type : entry.getValue().provides()) {
                DomainId prev = seen.put(type, entry.getKey());
                if (prev != null) {
                    throw new IllegalStateException(
                        "NodeType '" + type.value() + "' provided by both '"
                        + prev.value() + "' and '" + entry.getKey().value()
                        + "' — each NodeType must be provided by exactly one domain.");
                }
            }
        }
    }

    private void validateUnsatisfiedRequires() {
        Set<NodeType> allProvided = new LinkedHashSet<>();
        for (var reg : domainConfigs.values()) allProvided.addAll(reg.provides());
        for (var entry : domainConfigs.entrySet()) {
            for (NodeType req : entry.getValue().requires()) {
                if (!allProvided.contains(req)) {
                    throw new IllegalStateException(
                        "Domain '" + entry.getKey().value() + "' requires NodeType '"
                        + req.value() + "' but no domain provides it.");
                }
            }
        }
    }

    List<DomainId> computeTopologicalOrder() {
        Map<NodeType, DomainId> providerOf = new LinkedHashMap<>();
        for (var entry : domainConfigs.entrySet())
            for (NodeType t : entry.getValue().provides())
                providerOf.put(t, entry.getKey());

        Map<DomainId, Set<DomainId>> dependsOn = new LinkedHashMap<>();
        Map<DomainId, Integer> inDegree = new LinkedHashMap<>();
        for (DomainId id : domainConfigs.keySet()) {
            dependsOn.put(id, new LinkedHashSet<>());
            inDegree.put(id, 0);
        }
        for (var entry : domainConfigs.entrySet()) {
            for (NodeType req : entry.getValue().requires()) {
                DomainId provider = providerOf.get(req);
                if (provider != null && !provider.equals(entry.getKey())
                        && dependsOn.get(entry.getKey()).add(provider)) {
                    inDegree.merge(entry.getKey(), 1, Integer::sum);
                }
            }
        }

        Deque<DomainId> queue = new ArrayDeque<>();
        for (var entry : inDegree.entrySet())
            if (entry.getValue() == 0) queue.add(entry.getKey());
        List<DomainId> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            DomainId current = queue.poll();
            sorted.add(current);
            for (var entry : dependsOn.entrySet()) {
                if (entry.getValue().contains(current)) {
                    int newDeg = inDegree.merge(entry.getKey(), -1, Integer::sum);
                    if (newDeg == 0) queue.add(entry.getKey());
                }
            }
        }
        if (sorted.size() != domainConfigs.size()) {
            throw new IllegalStateException(
                "Circular dependency detected among domains: "
                + domainConfigs.keySet().stream()
                    .filter(id -> !sorted.contains(id))
                    .map(DomainId::value)
                    .toList());
        }
        return List.copyOf(sorted);
    }

    private void validateNodeIdUniqueness() {
        Map<NodeId, DomainId> seen = new LinkedHashMap<>();
        Map<NodeId, DesiredNode> nodeIndex = new LinkedHashMap<>();
        for (DomainId domainId : topologicalOrder) {
            var reg = domainConfigs.get(domainId);
            DesiredStateGraph graph = new DomainPhaseState(reg.compilationResult(), 0).currentGraph();
            for (var entry : graph.nodes().entrySet()) {
                DomainId prev = seen.get(entry.getKey());
                if (prev != null) {
                    DesiredNode prevNode = nodeIndex.get(entry.getKey());
                    if (!prevNode.equals(entry.getValue())) {
                        throw new IllegalStateException(
                            "Node ID '" + entry.getKey().value() + "' exists in both domain '"
                            + prev.value() + "' and '" + domainId.value()
                            + "' with different specs.");
                    }
                } else {
                    seen.put(entry.getKey(), domainId);
                    nodeIndex.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private void buildSortedRecompilers() {
        sortedRecompilers = recompilerIndex.entrySet().stream()
            .sorted(Comparator.comparingInt(e -> e.getKey().priority()))
            .map(e -> Map.entry(e.getKey(), e.getValue()))
            .toList();
    }

    Map<DomainId, DomainRegistration> domainConfigs() { return domainConfigs; }
    ConcurrentHashMap<String, TenantCompositionState> tenantStates() { return tenantStates; }

    DesiredStateGraph recompose(String tenancyId, TenantCompositionState tenantState) {
        DesiredStateGraph composed = graphFactory.empty();
        for (DomainId domainId : topologicalOrder) {
            DomainPhaseState phaseState = tenantState.phases().get(domainId);
            composed = composed.overlay(phaseState.currentGraph());
        }
        composed = addCrossDomainEdges(composed, tenantState);
        return composed;
    }

    public DesiredStateGraph buildMetaGraph() {
        Map<NodeType, DomainId> providerOf = new LinkedHashMap<>();
        for (var entry : domainConfigs.entrySet()) {
            for (NodeType t : entry.getValue().provides()) {providerOf.put(t, entry.getKey());}
        }

        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency>  deps  = new ArrayList<>();
        for (DomainId id : topologicalOrder) {
            var reg    = domainConfigs.get(id);
            var spec   = new DomainNodeSpec(id, reg);
            var nodeId = NodeId.of("domain:" + id.value());
            nodes.add(new DesiredNode(nodeId, spec, io.casehub.desiredstate.api.HumanGating.NONE));

            for (NodeType req : reg.requires()) {
                DomainId provider = providerOf.get(req);
                if (provider != null) {
                    deps.add(new Dependency(nodeId, NodeId.of("domain:" + provider.value())));
                }
            }
        }
        return graphFactory.of(nodes, deps);
    }


    private DesiredStateGraph addCrossDomainEdges(DesiredStateGraph composed, TenantCompositionState tenantState) {
        Map<NodeType, DomainId> providerOf = new LinkedHashMap<>();
        for (DomainId id : topologicalOrder)
            for (NodeType t : domainConfigs.get(id).provides())
                providerOf.put(t, id);

        for (DomainId domainId : topologicalOrder) {
            var reg = domainConfigs.get(domainId);
            if (reg.requires().isEmpty()) continue;

            Set<NodeId> dependentRoots = tenantState.phases().get(domainId).currentGraph().roots();
            for (NodeType reqType : reg.requires()) {
                DomainId providerId = providerOf.get(reqType);
                if (providerId == null) continue;
                DesiredStateGraph providerGraph = tenantState.phases().get(providerId).currentGraph();
                for (var nodeEntry : providerGraph.nodes().entrySet()) {
                    if (nodeEntry.getValue().type().equals(reqType)) {
                        for (NodeId root : dependentRoots) {
                            composed = composed.withDependency(
                                new Dependency(root, nodeEntry.getKey()));
                        }
                    }
                }
            }
        }
        return composed;
    }


    public void setTenantState(String tenancyId, TenantCompositionState state) {
        tenantStates.put(tenancyId, state);
    }

    public TenantCompositionState getTenantState(String tenancyId) {
        return tenantStates.get(tenancyId);
    }


    public Optional<CompilationResult> handleReplan(
            String tenancyId, ActualState actual,
            io.casehub.ras.api.ActiveSituation situation, DesiredStateGraphFactory factory) {
        TenantCompositionState tenantState = tenantStates.get(tenancyId);
        if (tenantState == null) { return Optional.empty(); }

        synchronized (recomposeLock) {
            TenantCompositionState current = tenantStates.get(tenancyId);
            if (current == null) { return Optional.empty(); }

            for (var entry : sortedRecompilers) {
                SituationRecompiler recompiler = entry.getKey();
                DomainId domainId = entry.getValue();
                DomainPhaseState phaseState = current.phases().get(domainId);
                DesiredStateGraph domainGraph = phaseState.currentGraph();

                Optional<CompilationResult> result = recompiler.recompile(
                        tenancyId, domainGraph, actual, situation, factory);
                if (result.isPresent()) {
                    current = current.withPhase(domainId,
                            current.phases().get(domainId).withResult(result.get()));
                    tenantStates.put(tenancyId, current);
                    return result;
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void onReconciliationCycleCompleted(String tenancyId, DesiredStateGraph desired, ActualState actual) {
        TenantCompositionState tenantState = tenantStates.get(tenancyId);
        if (tenantState == null) {return;}

        synchronized (recomposeLock) {
            boolean                recomposeNeeded = false;
            TenantCompositionState current         = tenantStates.get(tenancyId);
            if (current == null) {return;}

            for (var entry : current.phases().entrySet()) {
                DomainId         domainId   = entry.getKey();
                DomainPhaseState phaseState = entry.getValue();
                if (!phaseState.hasLifecycle() || phaseState.isAtFinalPhase()) {continue;}

                CompilationResult.Lifecycle                     lc           = (CompilationResult.Lifecycle) phaseState.currentResult();
                Phase currentPhase = lc.phases().get(phaseState.phaseIndex());
                CompletionCondition condition    = currentPhase.completionCondition();

                DesiredStateGraph domainGraph = phaseState.currentGraph();
                if (condition.isComplete(domainGraph, actual)) {
                    current         = current.withPhase(domainId, phaseState.withAdvancedPhase());
                    recomposeNeeded = true;
                    LOG.info("Domain '" + domainId.value() + "' phase transition: '"
                             + currentPhase.id() + "' → '"
                             + lc.phases().get(phaseState.phaseIndex() + 1).id() + "'");
                }
            }

            if (recomposeNeeded) {
                tenantStates.put(tenancyId, current);
            }
        }
    }

    @Override
    public void onTenantStopped(String tenancyId) {
        tenantStates.remove(tenancyId);
    }
}
