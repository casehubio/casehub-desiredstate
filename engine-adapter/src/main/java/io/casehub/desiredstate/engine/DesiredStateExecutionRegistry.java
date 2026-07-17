package io.casehub.desiredstate.engine;

import io.casehub.desiredstate.api.DesiredStateGraph;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DesiredStateExecutionRegistry {

    private final ConcurrentHashMap<String, DesiredStateExecutionContext> contexts =
        new ConcurrentHashMap<>();

    public void register(String executionId, DesiredStateGraph graph, String tenancyId) {
        contexts.put(executionId, new DesiredStateExecutionContext(graph, tenancyId));
    }

    public DesiredStateExecutionContext get(String executionId) {
        DesiredStateExecutionContext ctx = contexts.get(executionId);
        if (ctx == null) {
            throw new IllegalStateException(
                "No execution context registered for: " + executionId);
        }
        return ctx;
    }

    public void remove(String executionId) {
        contexts.remove(executionId);
    }

    private final ConcurrentHashMap<String, UUID> activeCases = new ConcurrentHashMap<>();

    public Optional<UUID> getActiveCaseId(String tenancyId) {
        return Optional.ofNullable(activeCases.get(tenancyId));
    }

    public void setActiveCaseId(String tenancyId, UUID caseId) {
        activeCases.put(tenancyId, caseId);
    }

    public void clearActiveCaseId(String tenancyId) {
        activeCases.remove(tenancyId);
    }

}
