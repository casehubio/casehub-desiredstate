package io.casehub.desiredstate.api;

@FunctionalInterface
public interface ReconciliationListener {
    void onReconciliationCycleCompleted(String tenancyId, DesiredStateGraph desired, ActualState actual);
}
