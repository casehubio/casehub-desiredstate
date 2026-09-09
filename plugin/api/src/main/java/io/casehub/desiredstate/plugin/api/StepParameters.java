package io.casehub.desiredstate.plugin.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class StepParameters {

    private final Map<String, Object> params;

    private StepParameters(Map<String, Object> params) {
        this.params = Collections.unmodifiableMap(params);
    }

    public static StepParameters of(Map<String, Object> params) {
        return new StepParameters(params);
    }

    public String getString(String key) {
        Object v = params.get(key);
        return v != null ? v.toString() : null;
    }

    public Integer getInt(String key) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) return Integer.parseInt(s);
        return null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String key) {
        Object v = params.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    public List<Object> getList(String key) {
        Object v = params.get(key);
        return v instanceof List<?> l ? (List<Object>) l : null;
    }

    public Object get(String key) {
        return params.get(key);
    }

    public Map<String, Object> asMap() {
        return params;
    }
}
