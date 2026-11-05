package io.casehub.eidos.org.runtime;

import java.util.Optional;

public interface OrgGoalStore {
    void store(String tenancyId, OrgGoal goal);
    Optional<OrgGoal> load(String tenancyId);
    void remove(String tenancyId);
}
