package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;

import java.util.Set;

/**
 * Provisions dungeon nodes (rooms, creatures, traps) in the {@link DungeonWorld}.
 * Handles both provisioning and deprovisioning operations.
 */
public class GoblinProvisioner implements NodeProvisioner {

    private final DungeonWorld world;

    public GoblinProvisioner(DungeonWorld world) {
        this.world = world;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(DungeonNodeTypes.ROOM, DungeonNodeTypes.CREATURE, DungeonNodeTypes.TRAP);
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        if (DungeonNodeTypes.ROOM.equals(node.type())) {
            if (world.roomState(node.id()) == DungeonWorld.State.BUILT) {
                return new ProvisionResult.AlreadyConverged();
            }
            world.setRoom(node.id(), DungeonWorld.State.BUILT);
            return new ProvisionResult.Success();
        } else if (DungeonNodeTypes.CREATURE.equals(node.type())) {
            if (world.creatureState(node.id()) == DungeonWorld.State.PRESENT) {
                return new ProvisionResult.AlreadyConverged();
            }
            world.setCreature(node.id(), DungeonWorld.State.PRESENT);
            return new ProvisionResult.Success();
        } else if (DungeonNodeTypes.TRAP.equals(node.type())) {
            if (world.trapState(node.id()) == DungeonWorld.State.ARMED) {
                return new ProvisionResult.AlreadyConverged();
            }
            world.setTrap(node.id(), DungeonWorld.State.ARMED);
            return new ProvisionResult.Success();
        }
        return new ProvisionResult.Failed("Unknown node type: " + node.type());
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        if (DungeonNodeTypes.ROOM.equals(node.type())) {
            world.destroyRoom(node.id());
            return new DeprovisionResult.Success();
        } else if (DungeonNodeTypes.CREATURE.equals(node.type())) {
            world.removeCreature(node.id());
            return new DeprovisionResult.Success();
        } else if (DungeonNodeTypes.TRAP.equals(node.type())) {
            world.setTrap(node.id(), DungeonWorld.State.TRIGGERED);
            return new DeprovisionResult.Success();
        }
        return new DeprovisionResult.Failed("Unknown node type: " + node.type());
    }
}
