package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.*;

import java.util.List;

/**
 * Fault policy that rebuilds destroyed dungeon nodes.
 * Responds to NODE_DESTROYED faults by adding the destroyed node back to the graph.
 */
public class HeroRaidFaultPolicy implements FaultPolicy {

    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current) {
        if (event.type() != FaultType.NODE_DESTROYED) {
            return List.of();
        }

        // Rebuild the destroyed node if it exists in the desired graph
        DesiredNode destroyedNode = current.nodes().get(event.node());
        if (destroyedNode == null) {
            return List.of();
        }

        return List.of(new GraphMutation.AddNode(destroyedNode));
    }
}
