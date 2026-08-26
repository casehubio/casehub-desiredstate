package io.casehub.desiredstate.annotations.runtime;

public final class GraphPatternMatcher {

    private GraphPatternMatcher() {}

    public static boolean matches(String[] patterns, String graphKey) {
        boolean result = false;
        for (String pattern : patterns) {
            if (pattern.startsWith("!")) {
                if (matchesSingle(pattern.substring(1), graphKey)) {
                    result = false;
                }
            } else {
                if (matchesSingle(pattern, graphKey)) {
                    result = true;
                }
            }
        }
        return result;
    }

    private static boolean matchesSingle(String pattern, String key) {
        if ("*:*".equals(pattern)) return true;
        if (pattern.endsWith(":*")) {
            String namespace = pattern.substring(0, pattern.length() - 1);
            return key.startsWith(namespace);
        }
        return pattern.equals(key);
    }
}
