package io.casehub.desiredstate.bridge.ansible;

import java.util.List;

public record AnsibleModuleConfig(
    String collection,
    String provisionModule,
    String deprovisionStrategy,
    String deprovisionModule,
    String infoModule,
    String infoFilterKey,
    String resultKey,
    List<String> outputKeys,
    List<String> driftKeys
) {
    public AnsibleModuleConfig {
        if (deprovisionStrategy == null) deprovisionStrategy = "state_absent";
        if (outputKeys == null) outputKeys = List.of();
        if (driftKeys == null) driftKeys = List.of();
    }

    public String fqProvisionModule() {
        return collection + "." + provisionModule;
    }

    public String fqInfoModule() {
        return infoModule != null ? collection + "." + infoModule : null;
    }

    public String fqDeprovisionModule() {
        return deprovisionModule != null ? collection + "." + deprovisionModule : null;
    }
}
