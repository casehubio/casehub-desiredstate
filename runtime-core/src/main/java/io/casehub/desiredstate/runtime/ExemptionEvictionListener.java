package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.ExemptionStore;
import io.casehub.desiredstate.api.GlobalReconciliationListener;

import java.util.Set;

public class ExemptionEvictionListener implements GlobalReconciliationListener {

    private final ExemptionStore store;

    public ExemptionEvictionListener(ExemptionStore store) {
        this.store = store;
    }

    @Override
    public void onReconciliationCycleCompleted(String tenancyId,
            DesiredStateGraph desired, ActualState actual) {
        store.evict(tenancyId, desired.nodes().keySet());
    }

    @Override
    public void onTenantStopped(String tenancyId) {
        store.evict(tenancyId, Set.of());
    }
}
