package io.casehub.desiredstate.bridge.ansible;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryGeneratorTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void defaultsToLocalhostWithLocalConnection() throws Exception {
        String inventory = InventoryGenerator.generate(Map.of());
        JsonNode root = json.readTree(inventory);
        var target = root.path("all").path("hosts").path("target");
        assertThat(target.path("ansible_host").asText()).isEqualTo("localhost");
        assertThat(target.path("ansible_connection").asText()).isEqualTo("local");
    }

    @Test
    void usesCustomHostAndConnection() throws Exception {
        String inventory = InventoryGenerator.generate(Map.of(
            "_host", "192.168.1.100",
            "_connection", "ssh"));
        JsonNode root = json.readTree(inventory);
        var target = root.path("all").path("hosts").path("target");
        assertThat(target.path("ansible_host").asText()).isEqualTo("192.168.1.100");
        assertThat(target.path("ansible_connection").asText()).isEqualTo("ssh");
    }

    @Test
    void includesBecomeWhenTrue() throws Exception {
        String inventory = InventoryGenerator.generate(Map.of("_become", true));
        JsonNode root = json.readTree(inventory);
        var target = root.path("all").path("hosts").path("target");
        assertThat(target.path("ansible_become").asBoolean()).isTrue();
    }

    @Test
    void includesBecomeMethod() throws Exception {
        String inventory = InventoryGenerator.generate(Map.of(
            "_become", true, "_become_method", "doas"));
        JsonNode root = json.readTree(inventory);
        var target = root.path("all").path("hosts").path("target");
        assertThat(target.path("ansible_become_method").asText()).isEqualTo("doas");
    }
}
