package io.casehub.desiredstate.yaml.model;

import java.util.List;

public record YamlLifecycle(List<YamlPhase> phases) {
    public YamlLifecycle {
        phases = phases != null ? phases : List.of();
    }
}
