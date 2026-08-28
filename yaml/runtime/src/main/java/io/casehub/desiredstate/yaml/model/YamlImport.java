package io.casehub.desiredstate.yaml.model;

import java.util.Map;

public record YamlImport(
        String module,
        String as,
        String when,
        Map<String, String> parameters) {

    public YamlImport {
        if (parameters == null) {parameters = Map.of();}
    }
}
