package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.Phase;
import io.casehub.desiredstate.api.ReconciliationListener;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class LifecycleManager {

    private static final Logger LOG = Logger.getLogger(LifecycleManager.class.getName());

    private final ReconciliationLoop loop;
    private final ConcurrentHashMap<String, TenantLifecycle> lifecycles = new ConcurrentHashMap<>();

    public LifecycleManager(ReconciliationLoop loop) {
        this.loop = loop;
    }

    public void start(String tenancyId, CompilationResult result) {
        ReconciliationListener listener = this::onCycleCompleted;
        switch (result) {
            case CompilationResult.SingleGraph sg ->
                loop.start(tenancyId, sg.graph(), listener);
            case CompilationResult.Lifecycle lc -> {
                lifecycles.put(tenancyId, new TenantLifecycle(lc.phases(), 0));
                loop.start(tenancyId, lc.phases().getFirst().graph(), listener);
            }
        }
    }

    public void stop(String tenancyId) {
        lifecycles.remove(tenancyId);
        loop.stop(tenancyId);
    }

    public void updateDesired(String tenancyId, CompilationResult result) {
        lifecycles.remove(tenancyId);
        switch (result) {
            case CompilationResult.SingleGraph sg ->
                loop.updateDesired(tenancyId, sg.graph());
            case CompilationResult.Lifecycle lc -> {
                lifecycles.put(tenancyId, new TenantLifecycle(lc.phases(), 0));
                loop.updateDesired(tenancyId, lc.phases().getFirst().graph());
                loop.setListener(tenancyId, this::onCycleCompleted);
            }
        }
    }

    private void onCycleCompleted(String tenancyId, DesiredStateGraph desired, ActualState actual) {
        TenantLifecycle current = lifecycles.get(tenancyId);
        if (current == null) {
            return;
        }

        Phase currentPhase = current.currentPhase();
        if (!currentPhase.completionCondition().isComplete(currentPhase.graph(), actual)) {
            return;
        }

        int nextIndex = current.phaseIndex + 1;
        if (nextIndex >= current.phases.size()) {
            lifecycles.remove(tenancyId);
            LOG.info("Lifecycle completed for tenant " + tenancyId
                + " — final phase '" + currentPhase.id() + "' complete");
            return;
        }

        Phase nextPhase = current.phases.get(nextIndex);
        TenantLifecycle next = new TenantLifecycle(current.phases, nextIndex);

        if (!lifecycles.replace(tenancyId, current, next)) {
            return;
        }

        if (!loop.compareAndSetDesired(tenancyId, desired, nextPhase.graph())) {
            lifecycles.replace(tenancyId, next, current);
            return;
        }

        LOG.info("Lifecycle transition for tenant " + tenancyId
            + ": '" + currentPhase.id() + "' → '" + nextPhase.id() + "'");
    }

    private record TenantLifecycle(List<Phase> phases, int phaseIndex) {
        Phase currentPhase() { return phases.get(phaseIndex); }
    }
}
