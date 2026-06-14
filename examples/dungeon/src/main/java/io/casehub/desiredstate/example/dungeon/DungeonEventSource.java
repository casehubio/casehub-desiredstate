package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.*;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;

/**
 * Event source for dungeon state changes.
 * Allows simulation of external events (hero raids, cave-ins, creature revolts).
 */
public class DungeonEventSource implements EventSource {

    private final BroadcastProcessor<StateEvent> emitter = BroadcastProcessor.create();

    @Override
    public Multi<StateEvent> stream() {
        return emitter;
    }

    /**
     * Simulate a hero raid that destroys a room.
     */
    public void heroRaid(String roomId) {
        emitter.onNext(new StateEvent(NodeId.of(roomId), NodeStatus.ABSENT, "Hero raid"));
    }

    /**
     * Simulate a cave-in that damages a tunnel/room.
     */
    public void caveIn(String tunnelId) {
        emitter.onNext(new StateEvent(NodeId.of(tunnelId), NodeStatus.DRIFTED, "Cave-in"));
    }

    /**
     * Simulate a creature revolt (creature flees or is killed).
     */
    public void creatureRevolt(String creatureId) {
        emitter.onNext(new StateEvent(NodeId.of(creatureId), NodeStatus.ABSENT, "Creature revolt"));
    }

    /**
     * Simulate treasury looting (generic event).
     */
    public void treasuryLooted() {
        emitter.onNext(new StateEvent(NodeId.of("treasury"), NodeStatus.ABSENT, "Treasury looted"));
    }
}
