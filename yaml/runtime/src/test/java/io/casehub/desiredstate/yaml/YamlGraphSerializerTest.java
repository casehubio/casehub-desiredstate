package io.casehub.desiredstate.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.api.DiscoveredNode;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class YamlGraphSerializerTest {

    private static final NodeType VM = NodeType.of("cloud:vm");
    private static final NodeType STORAGE = NodeType.of("cloud:storage");

    @Test
    void serializesDiscoveredResourcesToYaml() throws Exception {
        var resources = List.of(
            new DiscoveredNode("i-abc123", VM, Map.of("status", "running"),
                Map.of("name", "web-server-1", "instance_type", "t3.medium")),
            new DiscoveredNode("my-bucket", STORAGE, Map.of("status", "available"),
                Map.of("name", "data-bucket", "region", "us-east-1"))
        );

        String yaml = YamlGraphSerializer.serialize("staging", "discovered-infra", resources);

        assertNotNull(yaml);
        assertTrue(yaml.contains("staging"));
        assertTrue(yaml.contains("discovered-infra"));
        assertTrue(yaml.contains("cloud:vm"));
        assertTrue(yaml.contains("cloud:storage"));
        assertTrue(yaml.contains("web-server-1"));
        assertTrue(yaml.contains("data-bucket"));

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        YamlGraph parsed = mapper.readValue(yaml, YamlGraph.class);
        assertEquals(2, parsed.nodes().size());
    }

    @Test
    void sanitizesResourceIdsToYamlKeys() throws Exception {
        var resources = List.of(
            new DiscoveredNode("i-ABC_123.test", VM, Map.of(), Map.of("name", "vm-1"))
        );

        String yaml = YamlGraphSerializer.serialize("ns", "name", resources);

        assertTrue(yaml.contains("i-abc-123-test"));
    }

    @Test
    void emptyResourceListProducesValidYaml() throws Exception {
        String yaml = YamlGraphSerializer.serialize("ns", "name", List.of());

        assertNotNull(yaml);
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        YamlGraph parsed = mapper.readValue(yaml, YamlGraph.class);
        assertTrue(parsed.nodes().isEmpty());
    }

    @Test
    void roundTripPreservesSpec() throws Exception {
        var resources = List.of(
            new DiscoveredNode("vm-1", VM, Map.of(),
                Map.of("name", "my-vm", "instance_type", "t3.medium", "ami", "ami-12345"))
        );

        String yaml = YamlGraphSerializer.serialize("staging", "infra", resources);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        YamlGraph parsed = mapper.readValue(yaml, YamlGraph.class);
        var node = parsed.nodes().get("vm-1");
        assertNotNull(node);
        assertEquals("cloud:vm", node.type());
        assertEquals("my-vm", node.spec().get("name"));
        assertEquals("t3.medium", node.spec().get("instance_type"));
        assertEquals("ami-12345", node.spec().get("ami"));
    }
}
