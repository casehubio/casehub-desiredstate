package io.casehub.desiredstate.runtime.composition;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DomainId;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class DomainActualStateAdapter implements ActualStateAdapter {

    private final DomainNodeProvisioner provisioner;
    private final Map<DomainId, DomainRegistration> domainConfigs;

    public DomainActualStateAdapter(DomainNodeProvisioner provisioner,
                                    Map<DomainId, DomainRegistration> domainConfigs) {
        this.provisioner = provisioner;
        this.domainConfigs = domainConfigs;
    }

    @Override
    public Set<NodeType> handledTypes() { return Set.of(DomainNodeSpec.DOMAIN_NODE_TYPE); }

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        Map<NodeId, NodeStatus> statuses = new LinkedHashMap<>();
        for (var entry : desired.nodes().entrySet()) {
            if (entry.getValue().spec() instanceof DomainNodeSpec dSpec) {
                DomainRegistration reg = domainConfigs.get(dSpec.domainId());
                if (reg != null && provisioner.checkReadiness(reg, new ActualState(Map.of()))) {
                    statuses.put(entry.getKey(), NodeStatus.PRESENT);
                } else {
                    statuses.put(entry.getKey(), NodeStatus.ABSENT);
                }
            }
        }
        return new ActualState(statuses);
    }
}
