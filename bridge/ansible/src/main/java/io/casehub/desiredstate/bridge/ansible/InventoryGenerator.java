package io.casehub.desiredstate.bridge.ansible;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public final class InventoryGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private InventoryGenerator() {}

    public static String generate(Map<String, Object> spec) {
        Map<String, Object> hostVars = new LinkedHashMap<>();
        hostVars.put("ansible_host", spec.getOrDefault("_host", "localhost"));
        hostVars.put("ansible_connection", spec.getOrDefault("_connection", "local"));

        Object become = spec.get("_become");
        if (become != null) {
            hostVars.put("ansible_become", become);
            Object becomeMethod = spec.get("_become_method");
            if (becomeMethod != null) {
                hostVars.put("ansible_become_method", becomeMethod);
            }
        }

        Map<String, Object> inventory = Map.of(
            "all", Map.of("hosts", Map.of("target", hostVars)));

        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(inventory);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to generate inventory JSON", e);
        }
    }
}
