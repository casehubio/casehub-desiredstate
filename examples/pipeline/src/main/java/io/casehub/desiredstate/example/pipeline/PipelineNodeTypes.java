package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeType;

import java.util.Optional;

public final class PipelineNodeTypes {
    public static final NodeType DATA_SOURCE = new NodeType("data-source");
    public static final NodeType SCHEMA = new NodeType("schema");
    public static final NodeType INGESTION = new NodeType("ingestion");
    public static final NodeType CLEANSER = new NodeType("cleanser");
    public static final NodeType ENRICHER = new NodeType("enricher");
    public static final NodeType VALIDATOR = new NodeType("validator");
    public static final NodeType TRANSFORMER = new NodeType("transformer");
    public static final NodeType SINK = new NodeType("sink");
    public static final NodeType AI_REVIEW = new NodeType("ai-review");
    public static final NodeType HUMAN_REVIEW = new NodeType("human-review");

    public static Optional<PipelineLayer> layerOf(NodeType type) {
        if (type.equals(DATA_SOURCE) || type.equals(SCHEMA) || type.equals(INGESTION)) {
            return Optional.of(PipelineLayer.BRONZE);
        } else if (type.equals(CLEANSER) || type.equals(ENRICHER) || type.equals(VALIDATOR)) {
            return Optional.of(PipelineLayer.SILVER);
        } else if (type.equals(TRANSFORMER) || type.equals(SINK)) {
            return Optional.of(PipelineLayer.GOLD);
        }
        return Optional.empty();
    }

    private PipelineNodeTypes() {}
}
