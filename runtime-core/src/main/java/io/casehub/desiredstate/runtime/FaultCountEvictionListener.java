package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultCountStore;
import io.casehub.desiredstate.api.GlobalReconciliationListener;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

@ApplicationScoped
public class FaultCountEvictionListener implements GlobalReconciliationListener {

    private final FaultCountStore store;

    @Inject
    public FaultCountEvictionListener(FaultCountStore store) {
        this.store = store;
    }

    @Override
    public void onReconciliationCycleCompleted(String tenancyId,
            DesiredStateGraph desired, ActualState actual) {
        store.evictAcrossNamespaces(tenancyId, desired.nodes().keySet());
    }

    @Override
    public void onTenantStopped(String tenancyId) {
        store.evictAcrossNamespaces(tenancyId, Set.of());
    }
}
