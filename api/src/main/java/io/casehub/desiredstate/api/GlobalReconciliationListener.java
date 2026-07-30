package io.casehub.desiredstate.api;

public interface GlobalReconciliationListener {
    void onReconciliationCycleCompleted(String tenancyId, DesiredStateGraph desired, ActualState actual);
}
