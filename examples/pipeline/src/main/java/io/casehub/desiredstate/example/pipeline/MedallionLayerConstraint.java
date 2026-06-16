package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeId;

import java.util.Optional;
import java.util.Set;

/**
 * Validates that a {@link DesiredStateGraph} respects medallion layer ordering.
 * <p>
 * Rules:
 * <ul>
 *   <li>A node may only depend on nodes in the same layer or the immediately adjacent lower layer</li>
 *   <li>Backward dependencies (lower layer depending on higher) are rejected</li>
 *   <li>Layer-skipping dependencies (Gold depending on Bronze, skipping Silver) are rejected</li>
 *   <li>Nodes without a layer (AI_REVIEW, HUMAN_REVIEW) are exempt</li>
 * </ul>
 */
public final class MedallionLayerConstraint {

    public static void validate(DesiredStateGraph graph) {
        for (var entry : graph.nodes().entrySet()) {
            NodeId nodeId = entry.getKey();
            DesiredNode node = entry.getValue();

            Optional<PipelineLayer> nodeLayer = PipelineNodeTypes.layerOf(node.type());
            if (nodeLayer.isEmpty()) {
                continue;
            }

            Set<NodeId> deps = graph.dependenciesOf(nodeId);
            for (NodeId depId : deps) {
                DesiredNode depNode = graph.nodes().get(depId);
                if (depNode == null) {
                    continue;
                }

                Optional<PipelineLayer> depLayer = PipelineNodeTypes.layerOf(depNode.type());
                if (depLayer.isEmpty()) {
                    continue;
                }

                int diff = nodeLayer.get().ordinal() - depLayer.get().ordinal();
                if (diff < 0) {
                    throw new LayerViolationException(
                        "Backward layer dependency: node '%s' (%s) depends on '%s' (%s)"
                            .formatted(nodeId.value(), nodeLayer.get(), depId.value(), depLayer.get()));
                }
                if (diff > 1) {
                    throw new LayerViolationException(
                        "Layer-skipping dependency: node '%s' (%s) depends on '%s' (%s) — skips intermediate layer"
                            .formatted(nodeId.value(), nodeLayer.get(), depId.value(), depLayer.get()));
                }
            }
        }
    }

    public static class LayerViolationException extends RuntimeException {
        public LayerViolationException(String message) {
            super(message);
        }
    }

    private MedallionLayerConstraint() {}
}
