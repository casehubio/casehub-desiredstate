package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class PipelineProvisioner implements NodeProvisioner {

    private final PipelineWorld world;
    private final AgentProvider agentProvider;
    private final List<ExecutionBackend> backends;

    public PipelineProvisioner(PipelineWorld world, AgentProvider agentProvider,
                               List<ExecutionBackend> backends) {
        this.world = world;
        this.agentProvider = agentProvider;
        this.backends = List.copyOf(backends);
    }

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(
            PipelineNodeTypes.DATA_SOURCE, PipelineNodeTypes.SCHEMA,
            PipelineNodeTypes.INGESTION, PipelineNodeTypes.CLEANSER,
            PipelineNodeTypes.ENRICHER, PipelineNodeTypes.VALIDATOR,
            PipelineNodeTypes.TRANSFORMER, PipelineNodeTypes.SINK,
            PipelineNodeTypes.AI_REVIEW, PipelineNodeTypes.HUMAN_REVIEW
        );
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        NodeType type = node.type();

        if (type.equals(PipelineNodeTypes.DATA_SOURCE)) {
            return provisionDataSource(node);
        }
        if (type.equals(PipelineNodeTypes.SCHEMA)) {
            return provisionSchema(node);
        }
        if (type.equals(PipelineNodeTypes.AI_REVIEW)) {
            return provisionAiReview(node, context);
        }
        if (type.equals(PipelineNodeTypes.HUMAN_REVIEW)) {
            return provisionHumanReview(node);
        }

        return dispatchToBackend(node, context);
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

        return dispatchToBackendDeprovision(node, context);
    }

    private ProvisionResult dispatchToBackend(DesiredNode node, ProvisionContext context) {
        List<ExecutionBackend> matching = backends.stream()
                .filter(b -> b.handles(node))
                .toList();
        if (matching.size() > 1) {
            throw new AmbiguousBackendException(node, matching);
        }
        if (matching.isEmpty()) {
            return new ProvisionResult.Failed(
                    "No execution backend for: " + node.id().value()
                            + " (type: " + node.type().value() + ")");
        }
        return matching.get(0).provision(node, context);
    }

    private DeprovisionResult dispatchToBackendDeprovision(DesiredNode node,
                                                           DeprovisionContext context) {
        List<ExecutionBackend> matching = backends.stream()
                .filter(b -> b.handles(node))
                .toList();
        if (matching.size() > 1) {
            throw new AmbiguousBackendException(node, matching);
        }
        if (matching.isEmpty()) {
            return new DeprovisionResult.Failed(
                    "No execution backend for: " + node.id().value()
                            + " (type: " + node.type().value() + ")");
        }
        return matching.get(0).deprovision(node, context);
    }

    private ProvisionResult provisionDataSource(DesiredNode node) {
        DataSourceSpec spec = (DataSourceSpec) node.spec();
        world.registerSource(node.id(),
                new PipelineWorld.DataSourceEntry(spec.name(), spec.format(), spec.uri()));
        return new ProvisionResult.Success();
    }

    private ProvisionResult provisionSchema(DesiredNode node) {
        SchemaSpec spec = (SchemaSpec) node.spec();
        world.registerSchema(spec.name(),
                new PipelineWorld.SchemaDefinition(spec.name(), spec.fields(), spec.version()));
        return new ProvisionResult.Success();
    }

    private ProvisionResult provisionAiReview(DesiredNode node, ProvisionContext context) {
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

    private ProvisionResult provisionHumanReview(DesiredNode node) {
        HumanReviewSpec spec = (HumanReviewSpec) node.spec();
        world.addReview(node.id(), spec.targetNodeId());
        return new ProvisionResult.Success();
    }
}
