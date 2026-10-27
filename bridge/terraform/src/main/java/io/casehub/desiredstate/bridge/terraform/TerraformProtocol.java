package io.casehub.desiredstate.bridge.terraform;

import java.util.Map;

public interface TerraformProtocol extends AutoCloseable {
    ProviderSchema getSchema();
    void configure(Map<String, Object> config);
    PlanResponse plan(String resourceType, Map<String, Object> priorState,
                      Map<String, Object> proposedState);
    ApplyResponse apply(String resourceType, Map<String, Object> priorState,
                        byte[] plannedPrivate);
    ReadResponse read(String resourceType, Map<String, Object> currentState);
}
