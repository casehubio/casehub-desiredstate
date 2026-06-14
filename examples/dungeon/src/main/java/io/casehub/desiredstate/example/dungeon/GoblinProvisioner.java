package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.*;

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
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        if (DungeonNodeTypes.ROOM.equals(node.type())) {
            world.setRoom(node.id(), DungeonWorld.State.BUILT);
            return new ProvisionResult.Success();
        } else if (DungeonNodeTypes.CREATURE.equals(node.type())) {
            world.setCreature(node.id(), DungeonWorld.State.PRESENT);
            return new ProvisionResult.Success();
        } else if (DungeonNodeTypes.TRAP.equals(node.type())) {
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
