package io.casehub.desiredstate.plugin.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class StepContext {

    private final Map<String, Object> spec;
    private final Map<String, Map<String, String>> auth;
    private final Map<String, StepResult> results;
    private final Map<String, Object> params;

    private StepContext(Map<String, Object> spec,
                        Map<String, Map<String, String>> auth,
                        Map<String, StepResult> results,
                        Map<String, Object> params) {
        this.spec = Collections.unmodifiableMap(spec);
        this.auth = Collections.unmodifiableMap(auth);
        this.results = new HashMap<>(results);
        this.params = Collections.unmodifiableMap(params);
    }

    public Map<String, Object> spec() {
        return spec;
    }

    public Map<String, String> auth(String name) {
        return auth.getOrDefault(name, Map.of());
    }

    public Map<String, Map<String, String>> allAuth() {
        return auth;
    }

    public StepResult result(String name) {
        return results.get(name);
    }

    public void addResult(String name, StepResult result) {
        results.put(name, result);
    }

    public Map<String, Object> params() {
        return params;
    }

    public Object resolve(String prefixedRef) {
        int dot = prefixedRef.indexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException(
                "Reference must be prefixed: " + prefixedRef);
        }
        String prefix = prefixedRef.substring(0, dot);
        String remainder = prefixedRef.substring(dot + 1);

        return switch (prefix) {
            case "spec" -> resolveDeep(spec, remainder);
            case "auth" -> resolveAuth(remainder);
            case "result" -> resolveResult(remainder);
            case "param" -> params.get(remainder);
            default -> throw new IllegalArgumentException(
                "Unknown prefix '" + prefix + "' in reference: " + prefixedRef);
        };
    }

    @SuppressWarnings("unchecked")
    private Object resolveDeep(Map<String, Object> map, String dotPath) {
        String[] segments = dotPath.split("\\.");
        Object current = map;
        for (String segment : segments) {
            if (current instanceof Map<?, ?> m) {
                current = m.get(segment);
            } else {
                return null;
            }
        }
        return current;
    }

    private Object resolveAuth(String remainder) {
        int dot = remainder.indexOf('.');
        if (dot < 0) {
            return auth.get(remainder);
        }
        String authName = remainder.substring(0, dot);
        String key = remainder.substring(dot + 1);
        Map<String, String> creds = auth.get(authName);
        return creds != null ? creds.get(key) : null;
    }

    private Object resolveResult(String remainder) {
        int dot = remainder.indexOf('.');
        if (dot < 0) {
            StepResult r = results.get(remainder);
            return r != null ? r.data() : null;
        }
        String resultName = remainder.substring(0, dot);
        String path = remainder.substring(dot + 1);
        StepResult r = results.get(resultName);
        return r != null ? r.get(path) : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Map<String, Object> spec = Map.of();
        private final Map<String, Map<String, String>> auth = new HashMap<>();
        private final Map<String, StepResult> results = new HashMap<>();
        private Map<String, Object> params = Map.of();

        public Builder spec(Map<String, Object> spec) {
            this.spec = spec;
            return this;
        }

        public Builder addAuth(String name, Map<String, String> credentials) {
            auth.put(name, credentials);
            return this;
        }

        public Builder addResult(String name, StepResult result) {
            results.put(name, result);
            return this;
        }

        public Builder params(Map<String, Object> params) {
            this.params = params;
            return this;
        }

        public StepContext build() {
            return new StepContext(spec, auth, results, params);
        }
    }
}
