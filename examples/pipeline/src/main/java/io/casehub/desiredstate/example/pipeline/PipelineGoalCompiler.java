package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a {@link PipelineBlueprint} into a {@link DesiredStateGraph}.
 * <p>
 * Dependencies are inferred from the canonical pipeline topology — the user
 * never declares them. The pipeline shape IS the dependency graph:
 * <pre>
 *   datasource ← ingestion ← cleanser ← enricher ← validator ← transformer ← sink
 *   schema ──────────────────→ cleanser
 *   schema ───────────────────────────────→ validator
 * </pre>
 */
public class PipelineGoalCompiler implements GoalCompiler<PipelineBlueprint> {

    @Override
    public CompilationResult compile(PipelineBlueprint goals, DesiredStateGraphFactory factory) {
        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency> dependencies = new ArrayList<>();

        // Collect node IDs per stage for canonical dependency wiring
        List<NodeId> sourceIds = new ArrayList<>();
        List<NodeId> schemaIds = new ArrayList<>();
        List<NodeId> ingestionIds = new ArrayList<>();
        List<NodeId> cleanserIds = new ArrayList<>();
        List<NodeId> enricherIds = new ArrayList<>();
        List<NodeId> validatorIds = new ArrayList<>();
        List<NodeId> transformerIds = new ArrayList<>();

        // --- Bronze layer: sources, schemas, ingestions ---

        for (PipelineBlueprint.SourceEntry src : goals.sources()) {
            NodeId id = NodeId.of(src.id());
            nodes.add(new DesiredNode(id, PipelineNodeTypes.DATA_SOURCE,
                new DataSourceSpec(src.id(), src.format(), src.uri()), false));
            sourceIds.add(id);
        }

        for (PipelineBlueprint.SchemaEntry schema : goals.schemas()) {
            NodeId id = NodeId.of(schema.id());
            nodes.add(new DesiredNode(id, PipelineNodeTypes.SCHEMA,
                new SchemaSpec(schema.id(), schema.fields(), schema.version()), false));
            schemaIds.add(id);
        }

        for (PipelineBlueprint.IngestionEntry ing : goals.ingestions()) {
            NodeId id = NodeId.of(ing.id());
            nodes.add(new DesiredNode(id, PipelineNodeTypes.INGESTION,
                new IngestionSpec(ing.sourceRef(), ing.batchSize(), ing.format()), false));
            ingestionIds.add(id);

            // ingestion depends on its datasource
            dependencies.add(new Dependency(id, NodeId.of(ing.sourceRef())));
        }

        // --- Silver layer: cleansers, enrichers, validators ---

        for (PipelineBlueprint.CleanserEntry cl : goals.cleansers()) {
            NodeId id = NodeId.of(cl.id());
            nodes.add(new DesiredNode(id, PipelineNodeTypes.CLEANSER,
                new CleanserSpec(cl.rules(), cl.deduplication(), cl.nullHandling()), false));
            cleanserIds.add(id);

            // cleanser depends on all ingestions
            for (NodeId ingId : ingestionIds) {
                dependencies.add(new Dependency(id, ingId));
            }
            // cleanser depends on all schemas
            for (NodeId schemaId : schemaIds) {
                dependencies.add(new Dependency(id, schemaId));
            }
        }

        for (PipelineBlueprint.EnricherEntry en : goals.enrichers()) {
            NodeId id = NodeId.of(en.id());
            nodes.add(new DesiredNode(id, PipelineNodeTypes.ENRICHER,
                new EnricherSpec(en.lookupSource(), en.joinKeys(), en.enrichFields()), false));
            enricherIds.add(id);

            // enricher depends on all cleansers
            for (NodeId clId : cleanserIds) {
                dependencies.add(new Dependency(id, clId));
            }
        }

        for (PipelineBlueprint.ValidatorEntry val : goals.validators()) {
            NodeId id = NodeId.of(val.id());
            nodes.add(new DesiredNode(id, PipelineNodeTypes.VALIDATOR,
                new ValidatorSpec(val.schemaRef(), val.qualityThreshold(), val.anomalyDetection()), false));
            validatorIds.add(id);

            // validator depends on all enrichers
            for (NodeId enId : enricherIds) {
                dependencies.add(new Dependency(id, enId));
            }
            // validator depends on all schemas
            for (NodeId schemaId : schemaIds) {
                dependencies.add(new Dependency(id, schemaId));
            }
        }

        // --- Gold layer: transformers, sinks ---

        for (PipelineBlueprint.TransformerEntry tx : goals.transformers()) {
            NodeId id = NodeId.of(tx.id());
            nodes.add(new DesiredNode(id, PipelineNodeTypes.TRANSFORMER,
                new TransformerSpec(tx.aggregations(), tx.reshapeRules(), tx.outputFormat()), false));
            transformerIds.add(id);

            // transformer depends on all validators
            for (NodeId valId : validatorIds) {
                dependencies.add(new Dependency(id, valId));
            }
        }

        for (PipelineBlueprint.SinkEntry sink : goals.sinks()) {
            NodeId id = NodeId.of(sink.id());
            nodes.add(new DesiredNode(id, PipelineNodeTypes.SINK,
                new SinkSpec(sink.destination(), sink.format(), sink.partitionKeys()), false));

            // sink depends on all transformers
            for (NodeId txId : transformerIds) {
                dependencies.add(new Dependency(id, txId));
            }
        }

        DesiredStateGraph graph = factory.of(nodes, dependencies);
        MedallionLayerConstraint.validate(graph);
        return CompilationResult.single(graph);
    }
}
