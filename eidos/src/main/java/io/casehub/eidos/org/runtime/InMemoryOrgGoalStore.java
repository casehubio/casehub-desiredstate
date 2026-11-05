package io.casehub.eidos.org.runtime;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryOrgGoalStore implements OrgGoalStore {

    private final ConcurrentHashMap<String, OrgGoal> goals = new ConcurrentHashMap<>();

    @Override
    public void store(String tenancyId, OrgGoal goal) {
        goals.put(tenancyId, goal);
    }

    @Override
    public Optional<OrgGoal> load(String tenancyId) {
        return Optional.ofNullable(goals.get(tenancyId));
    }

    @Override
    public void remove(String tenancyId) {
        goals.remove(tenancyId);
    }
}
