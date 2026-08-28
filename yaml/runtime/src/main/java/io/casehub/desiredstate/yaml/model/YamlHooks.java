package io.casehub.desiredstate.yaml.model;

import java.util.List;
import java.util.Map;

public record YamlHooks(List<Map<String, Object>> pre, List<Map<String, Object>> post) {
    public YamlHooks {
        if (pre == null) pre = List.of();
        if (post == null) post = List.of();
    }
}
