package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeId;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/pipeline")
public class PipelineVisualizer {

    @Inject
    PipelineWorld world;

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<PipelineSnapshot> stream() {
        return Multi.createFrom().ticks().every(Duration.ofMillis(500))
            .map(tick -> snapshot());
    }

    private PipelineSnapshot snapshot() {
        Map<String, StageView> bronze = new LinkedHashMap<>();
        Map<String, StageView> silver = new LinkedHashMap<>();
        Map<String, StageView> gold = new LinkedHashMap<>();
        Map<String, ReviewView> reviews = new LinkedHashMap<>();
        Map<String, SchemaView> schemas = new LinkedHashMap<>();

        for (Map.Entry<NodeId, PipelineWorld.StageEntry> entry : world.allStages().entrySet()) {
            PipelineWorld.StageEntry stage = entry.getValue();
            StageView view = new StageView(stage.state(), stage.processed(), stage.failed(), stage.quarantined());
            String id = entry.getKey().value();
            PipelineNodeTypes.layerOf(stage.nodeType()).ifPresent(layer -> {
                switch (layer) {
                    case BRONZE -> bronze.put(id, view);
                    case SILVER -> silver.put(id, view);
                    case GOLD -> gold.put(id, view);
                }
            });
        }

        for (Map.Entry<NodeId, PipelineWorld.ReviewEntry> entry : world.allReviews().entrySet()) {
            PipelineWorld.ReviewEntry review = entry.getValue();
            String nodeType = entry.getKey().value().startsWith("ai-review") ? "AI_REVIEW" : "HUMAN_REVIEW";
            reviews.put(entry.getKey().value(),
                new ReviewView(nodeType, review.targetNode().value(), "", review.state()));
        }

        for (Map.Entry<String, PipelineWorld.SchemaDefinition> entry : world.allSchemas().entrySet()) {
            PipelineWorld.SchemaDefinition schema = entry.getValue();
            schemas.put(entry.getKey(), new SchemaView(schema.name(), schema.version(), schema.fields()));
        }

        return new PipelineSnapshot(bronze, silver, gold, reviews, schemas);
    }

    public record PipelineSnapshot(
        Map<String, StageView> bronzeStages,
        Map<String, StageView> silverStages,
        Map<String, StageView> goldStages,
        Map<String, ReviewView> activeReviews,
        Map<String, SchemaView> schemas
    ) {}

    public record StageView(PipelineWorld.StageState state, long processed, long failed, long quarantined) {}
    public record ReviewView(String nodeType, String targetStageId, String reason, PipelineWorld.ReviewState state) {}
    public record SchemaView(String name, int version, List<String> fields) {}
}
