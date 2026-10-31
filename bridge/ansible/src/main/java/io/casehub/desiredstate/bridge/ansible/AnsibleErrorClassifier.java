package io.casehub.desiredstate.bridge.ansible;

import io.casehub.desiredstate.bridge.BridgeErrorCategory;

import java.util.Locale;

public final class AnsibleErrorClassifier {

    private AnsibleErrorClassifier() {}

    public static BridgeErrorCategory classify(AnsibleTaskResult taskResult) {
        String status = taskResult.status();
        if ("unreachable".equals(status) || "timeout".equals(status)) {
            return BridgeErrorCategory.TRANSIENT;
        }

        String msg = taskResult.msg() != null ? taskResult.msg().toLowerCase(Locale.ROOT) : "";

        if (matchesAny(msg, "auth", "credential", "unauthorized",
                "accessdenied", "forbidden", "permission denied")) {
            return BridgeErrorCategory.AUTHENTICATION;
        }

        if (matchesAny(msg, "required", "missing parameter", "unknown module",
                "not found in collection", "invalid")) {
            return BridgeErrorCategory.CONFIGURATION;
        }

        if (matchesAny(msg, "limit", "quota", "exceeded", "capacity", "too many")) {
            return BridgeErrorCategory.QUOTA;
        }

        return BridgeErrorCategory.TRANSIENT;
    }

    private static boolean matchesAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}
