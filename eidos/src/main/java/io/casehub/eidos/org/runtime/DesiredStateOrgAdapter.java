package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.eidos.org.api.GroupLifecycleState;
import io.casehub.eidos.org.api.OrgRegistry;
import io.casehub.eidos.org.api.spi.GroupInstanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class DesiredStateOrgAdapter implements ActualStateAdapter {

    private final GroupInstanceStore instanceStore;
    private final OrgRegistry registry;

    @Inject
    public DesiredStateOrgAdapter(GroupInstanceStore instanceStore, OrgRegistry registry) {
        this.instanceStore = instanceStore;
        this.registry = registry;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(OrgNodeTypes.UNIT, OrgNodeTypes.RELATIONSHIP);
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        Map<NodeId, NodeStatus> statuses = new HashMap<>();

        for (Map.Entry<NodeId, DesiredNode> entry : desired.nodes().entrySet()) {
            NodeId id = entry.getKey();
            DesiredNode node = entry.getValue();

            if (node.type().equals(OrgNodeTypes.UNIT)) {
                statuses.put(id, readUnitStatus(id, tenancyId));
            } else if (node.type().equals(OrgNodeTypes.RELATIONSHIP)) {
                statuses.put(id, readRelationshipStatus(node, tenancyId));
            }
        }

        return new ActualState(statuses);
    }

    private NodeStatus readUnitStatus(NodeId nodeId, String tenancyId) {
        return instanceStore.findById(nodeId.value(), tenancyId)
            .map(instance -> mapLifecycleState(instance.state()))
            .orElse(NodeStatus.ABSENT);
    }

    private NodeStatus mapLifecycleState(GroupLifecycleState state) {
        return switch (state) {
            case ACTIVE -> NodeStatus.PRESENT;
            case REORGANISING -> NodeStatus.DRIFTED;
            case DISSOLVED, REJECTED, FAILED -> NodeStatus.ABSENT;
            case PROPOSED, FORMING, DISSOLVING -> NodeStatus.ABSENT;
        };
    }

    private NodeStatus readRelationshipStatus(DesiredNode node, String tenancyId) {
        if (!(node.spec() instanceof OrgRelationshipNodeSpec relSpec)) {
            return NodeStatus.UNKNOWN;
        }
        var rel = relSpec.relationship();
        var existing = registry.relationshipsFrom(rel.sourceAgentId(), tenancyId);
        boolean found = existing.stream().anyMatch(r ->
            r.targetAgentId().equals(rel.targetAgentId()) && r.kind() == rel.kind());
        return found ? NodeStatus.PRESENT : NodeStatus.ABSENT;
    }
}
