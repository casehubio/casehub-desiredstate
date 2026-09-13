package io.casehub.desiredstate.runtime.composition;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.runtime.ReconciliationLoop;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class DomainNodeProvisioner implements NodeProvisioner {

    static final NodeType DOMAIN_TYPE = DomainNodeSpec.DOMAIN_NODE_TYPE;

    private final DesiredStateGraphFactory graphFactory;
    private final Map<io.casehub.desiredstate.api.DomainId, ReconciliationLoop> activeInnerLoops = new LinkedHashMap<>();

    public DomainNodeProvisioner(DesiredStateGraphFactory graphFactory) {
        this.graphFactory = graphFactory;
    }

    @Override
    public Set<NodeType> handledTypes() { return Set.of(DOMAIN_TYPE); }

    @Override
    public Duration resyncInterval() { return Duration.ofSeconds(30); }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        DomainNodeSpec spec = (DomainNodeSpec) node.spec();
        if (checkReadiness(spec.registration(), readActualForDomain(spec, context))) {
            return new ProvisionResult.Success();
        }
        return new ProvisionResult.Failed("domain '" + spec.domainId().value()
            + "' not ready — awaiting convergence");
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        DomainNodeSpec spec = (DomainNodeSpec) node.spec();
        ReconciliationLoop innerLoop = activeInnerLoops.remove(spec.domainId());
        if (innerLoop != null) {
            innerLoop.stop(context.tenancyId());
            innerLoop.shutdown();
        }
        return new DeprovisionResult.Success();
    }

    public boolean checkReadiness(DomainRegistration reg, ActualState actual) {
        DesiredStateGraph graph = new DomainPhaseState(reg.compilationResult(), 0).currentGraph();
        return reg.readinessCondition().isComplete(graph, actual);
    }

    private ActualState readActualForDomain(DomainNodeSpec spec, ProvisionContext context) {
        return new ActualState(Map.of());
    }

    Map<io.casehub.desiredstate.api.DomainId, ReconciliationLoop> activeInnerLoops() {
        return activeInnerLoops;
    }
}
