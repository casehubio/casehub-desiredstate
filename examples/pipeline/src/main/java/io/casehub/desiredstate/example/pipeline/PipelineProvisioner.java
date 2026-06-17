package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Provisions and deprovisions pipeline nodes by mutating the {@link PipelineWorld}.
 * Dispatches on {@link NodeType} to handle each kind of pipeline entity.
 */
public class PipelineProvisioner implements NodeProvisioner {

    private final PipelineWorld world;
    private final AgentProvider agentProvider;

    public PipelineProvisioner(PipelineWorld world, AgentProvider agentProvider) {
        this.world = world;
        this.agentProvider = agentProvider;
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        NodeType type = node.type();

        if (type.equals(PipelineNodeTypes.DATA_SOURCE)) {
            DataSourceSpec spec = (DataSourceSpec) node.spec();
            world.registerSource(node.id(),
                new PipelineWorld.DataSourceEntry(spec.name(), spec.format(), spec.uri()));
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.SCHEMA)) {
            SchemaSpec spec = (SchemaSpec) node.spec();
            world.registerSchema(spec.name(),
                new PipelineWorld.SchemaDefinition(spec.name(), spec.fields(), spec.version()));
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.INGESTION)) {
            IngestionSpec spec = (IngestionSpec) node.spec();
            if (!world.hasSource(NodeId.of(spec.sourceRef()))) {
                return new ProvisionResult.Failed(
                    "Data source not found: " + spec.sourceRef());
            }
            world.setStage(node.id(), runningStage(type));
            registerDownstream(node.id(), context.graph());
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.CLEANSER)) {
            Set<NodeId> deps = context.graph().dependenciesOf(node.id());
            boolean hasUpstream = deps.stream().anyMatch(dep -> {
                DesiredNode depNode = context.graph().nodes().get(dep);
                return depNode != null && PipelineNodeTypes.INGESTION.equals(depNode.type());
            });
            if (!hasUpstream) {
                return new ProvisionResult.Failed("No upstream ingestion stage found");
            }
            world.setStage(node.id(), runningStage(type));
            registerDownstream(node.id(), context.graph());
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.ENRICHER)) {
            EnricherSpec spec = (EnricherSpec) node.spec();
            if (!world.hasLookupSource(spec.lookupSource())) {
                return new ProvisionResult.Failed(
                    "Lookup source not found: " + spec.lookupSource());
            }
            world.setStage(node.id(), runningStage(type));
            registerDownstream(node.id(), context.graph());
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.VALIDATOR)) {
            ValidatorSpec spec = (ValidatorSpec) node.spec();
            if (!world.hasSchema(spec.schemaRef())) {
                return new ProvisionResult.Failed(
                    "Schema not found: " + spec.schemaRef());
            }
            world.setStage(node.id(), runningStage(type));
            registerDownstream(node.id(), context.graph());
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.TRANSFORMER)) {
            world.setStage(node.id(), runningStage(type));
            registerDownstream(node.id(), context.graph());
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.SINK)) {
            world.setStage(node.id(), runningStage(type));
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.AI_REVIEW)) {
            AiReviewSpec spec = (AiReviewSpec) node.spec();
            NodeId target = spec.targetNodeId();

            PipelineWorld.ReviewEntry existing = world.review(node.id());
            if (existing != null) {
                if (existing.state() == PipelineWorld.ReviewState.RESOLVED) {
                    world.clearStageError(target);
                }
                return new ProvisionResult.Success();
            }

            String diagnosis = agentProvider.invoke(AgentSessionConfig.of(
                    "You are a data pipeline fault diagnostic agent. Analyze the error and determine if you can resolve it. Respond with RESOLVED if the issue can be fixed automatically, or UNRESOLVED if human intervention is needed.",
                    "Node " + target.value() + " failed with: " + spec.errorDetail(),
                    Duration.ofSeconds(30)
            )).filter(AgentEvent.TextDelta.class::isInstance)
                    .map(AgentEvent.TextDelta.class::cast)
                    .map(AgentEvent.TextDelta::text)
                    .collect().asList()
                    .onItem().transform(texts -> String.join("", texts))
                    .await().atMost(Duration.ofSeconds(30));

            if (diagnosis.isEmpty()) {
                world.addReview(node.id(), target);
                return new ProvisionResult.Success();
            }

            String upper = diagnosis.toUpperCase(Locale.ROOT);
            boolean resolved = upper.contains("RESOLVED") && !upper.contains("UNRESOLVED");
            world.addReview(node.id(), target);
            world.setAiReviewOutcome(target, resolved);
            return new ProvisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.HUMAN_REVIEW)) {
            HumanReviewSpec spec = (HumanReviewSpec) node.spec();
            world.addReview(node.id(), spec.targetNodeId());
            return new ProvisionResult.Success();
        }

        return new ProvisionResult.Failed("Unknown node type: " + type.value());
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        NodeType type = node.type();

        if (type.equals(PipelineNodeTypes.DATA_SOURCE)) {
            world.removeSource(node.id());
            return new DeprovisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.SCHEMA)) {
            SchemaSpec spec = (SchemaSpec) node.spec();
            world.removeSchema(spec.name());
            return new DeprovisionResult.Success();
        }

        if (type.equals(PipelineNodeTypes.AI_REVIEW) || type.equals(PipelineNodeTypes.HUMAN_REVIEW)) {
            world.removeReview(node.id());
            return new DeprovisionResult.Success();
        }

        // All processing stages: remove with downstream cascade
        world.removeStage(node.id());
        return new DeprovisionResult.Success();
    }

    private void registerDownstream(NodeId nodeId, DesiredStateGraph graph) {
        Set<NodeId> dependents = graph.dependentsOf(nodeId);
        for (NodeId dependent : dependents) {
            world.registerDownstream(nodeId, dependent);
        }
    }

    private PipelineWorld.StageEntry runningStage(NodeType nodeType) {
        return new PipelineWorld.StageEntry(
            nodeType, PipelineWorld.StageState.RUNNING, null, null, 0, 0, 0, null);
    }
}
