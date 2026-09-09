package io.casehub.desiredstate.plugin.api;

import java.util.Collections;
import java.util.Map;

public final class StepResult {

    private final Map<String, Object> data;

    private StepResult(Map<String, Object> data) {
        this.data = Collections.unmodifiableMap(data);
    }

    public static StepResult of(Map<String, Object> data) {
        return new StepResult(data);
    }

    public static StepResult empty() {
        return new StepResult(Map.of());
    }

    @SuppressWarnings("unchecked")
    public Object get(String dotPath) {
        String[] segments = dotPath.split("\\.");
        Object current = data;
        for (String segment : segments) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else {
                return null;
            }
        }
        return current;
    }

    public Map<String, Object> data() {
        return data;
    }
}
