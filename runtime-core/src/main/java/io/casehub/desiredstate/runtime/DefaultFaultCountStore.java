package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.InMemoryFaultCountStore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@DefaultBean
@ApplicationScoped
public class DefaultFaultCountStore extends InMemoryFaultCountStore {
}
