package io.casehub.desiredstate.api;

import java.util.Optional;
import java.util.Set;

public interface ReconciliationStateStore {
    void store(String tenancyId, DesiredStateGraph lastReconciledDesired);
    Optional<DesiredStateGraph> load(String tenancyId);
    void remove(String tenancyId);

    default Set<String> allTenancyIds() {
        throw new UnsupportedOperationException("allTenancyIds not implemented");
    }
}
