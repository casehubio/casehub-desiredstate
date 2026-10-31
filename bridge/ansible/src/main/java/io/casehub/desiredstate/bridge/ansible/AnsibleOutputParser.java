package io.casehub.desiredstate.bridge.ansible;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AnsibleOutputParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AnsibleOutputParser() {}

    @SuppressWarnings("unchecked")
    public static AnsiblePlaybookResult parse(String jsonOutput) {
        try {
            JsonNode root = JSON.readTree(jsonOutput);
            JsonNode stats = root.path("stats").path("target");
            boolean failed = stats.path("failed").asInt() > 0
                || stats.path("unreachable").asInt() > 0;

            JsonNode plays = root.path("plays");
            if (!plays.isArray() || plays.isEmpty()) {
                return new AnsiblePlaybookResult(
                    new AnsibleTaskResult("failed", "No plays executed", false), false);
            }

            JsonNode tasks = plays.get(0).path("tasks");
            if (!tasks.isArray() || tasks.isEmpty()) {
                return new AnsiblePlaybookResult(
                    new AnsibleTaskResult("failed", "No tasks executed", false), false);
            }

            JsonNode hostResult = tasks.get(0).path("hosts").path("target");
            boolean changed = hostResult.path("changed").asBoolean(false);
            boolean taskFailed = hostResult.path("failed").asBoolean(false);
            boolean unreachable = hostResult.path("unreachable").asBoolean(false);
            String msg = hostResult.path("msg").asText("");

            String status = unreachable ? "unreachable" : taskFailed ? "failed" : "ok";
            Map<String, Object> resultMap = JSON.convertValue(hostResult, Map.class);

            return new AnsiblePlaybookResult(
                new AnsibleTaskResult(status, msg, changed, resultMap), !failed);
        } catch (IOException e) {
            return new AnsiblePlaybookResult(
                new AnsibleTaskResult("failed", "Failed to parse output: " + e.getMessage(), false),
                false);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractOutputState(AnsibleTaskResult taskResult,
                                                          AnsibleModuleConfig config) {
        Map<String, Object> rawResult = taskResult.result();

        Object collection = rawResult.get(config.resultKey());
        Map<String, Object> source;
        if (collection instanceof List<?> list && !list.isEmpty()) {
            source = (Map<String, Object>) list.get(0);
        } else if (collection instanceof Map) {
            source = (Map<String, Object>) collection;
        } else {
            source = rawResult;
        }

        if (config.outputKeys().isEmpty()) {
            return new LinkedHashMap<>(source);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        for (String key : config.outputKeys()) {
            if (source.containsKey(key)) {
                output.put(key, source.get(key));
            }
        }
        return output;
    }

    public static boolean detectDrift(Map<String, Object> actual, Map<String, Object> desired,
                                       List<String> driftKeys) {
        if (driftKeys.isEmpty()) return false;
        for (String key : driftKeys) {
            Object actualVal = actual.get(key);
            Object desiredVal = desired.get(key);
            if (!Objects.equals(actualVal, desiredVal)) {
                return true;
            }
        }
        return false;
    }
}
