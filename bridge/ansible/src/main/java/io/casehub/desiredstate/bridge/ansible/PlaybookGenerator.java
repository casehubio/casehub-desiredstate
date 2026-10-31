package io.casehub.desiredstate.bridge.ansible;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlaybookGenerator {

    private static final ObjectMapper YAML = new ObjectMapper(
        new YAMLFactory().enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    private PlaybookGenerator() {}

    public static String provision(String fqModule, Map<String, Object> params) {
        return buildPlaybook("provision", fqModule, params, false);
    }

    public static String deprovision(String fqModule, Map<String, Object> params, String strategy) {
        Map<String, Object> taskParams = new LinkedHashMap<>(params);
        if ("state_absent".equals(strategy)) {
            taskParams.put("state", "absent");
        }
        return buildPlaybook("deprovision", fqModule, taskParams, false);
    }

    public static String readState(String fqInfoModule, String filterKey, Object filterValue) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(filterKey, filterValue);
        return buildPlaybook("read_state", fqInfoModule, params, false);
    }

    public static String check(String fqModule, Map<String, Object> params) {
        return buildPlaybook("check", fqModule, params, true);
    }

    private static String buildPlaybook(String taskName, String module,
                                         Map<String, Object> params, boolean checkMode) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("name", taskName);
        task.put(module, params.isEmpty() ? Map.of() : new LinkedHashMap<>(params));
        task.put("register", "result");
        if (checkMode) {
            task.put("check_mode", true);
        }

        Map<String, Object> play = new LinkedHashMap<>();
        play.put("hosts", "target");
        play.put("gather_facts", false);
        play.put("tasks", List.of(task));

        try {
            return YAML.writeValueAsString(List.of(play));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to generate playbook YAML", e);
        }
    }
}
