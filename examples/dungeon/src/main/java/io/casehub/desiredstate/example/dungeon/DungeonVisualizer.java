package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.NodeId;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * JAX-RS resource that streams DungeonWorld state via Server-Sent Events.
 * Provides a real-time view of the dungeon's rooms, creatures, and traps.
 */
@Path("/dungeon")
public class DungeonVisualizer {

    @Inject
    DungeonWorld world;

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<DungeonSnapshot> stream() {
        return Multi.createFrom().ticks().every(Duration.ofMillis(500))
            .map(tick -> new DungeonSnapshot(
                toStringMap(world.allRooms()),
                toStringMap(world.allCreatures()),
                toStringMap(world.allTraps())));
    }

    private Map<String, DungeonWorld.State> toStringMap(Map<NodeId, DungeonWorld.State> source) {
        return source.entrySet().stream()
            .collect(Collectors.toMap(
                e -> e.getKey().value(),
                Map.Entry::getValue
            ));
    }

    public record DungeonSnapshot(
        Map<String, DungeonWorld.State> rooms,
        Map<String, DungeonWorld.State> creatures,
        Map<String, DungeonWorld.State> traps) {}
}
