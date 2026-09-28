package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.InMemoryReconciliationStateStore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@DefaultBean
@ApplicationScoped
public class DefaultReconciliationStateStore extends InMemoryReconciliationStateStore {
}
