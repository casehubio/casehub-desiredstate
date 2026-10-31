package io.casehub.desiredstate.bridge.ansible;

import java.util.Map;

public record AnsibleTaskResult(
    String status,
    String msg,
    boolean changed,
    Map<String, Object> result
) {
    public AnsibleTaskResult(String status, String msg, boolean changed) {
        this(status, msg, changed, Map.of());
    }
}
