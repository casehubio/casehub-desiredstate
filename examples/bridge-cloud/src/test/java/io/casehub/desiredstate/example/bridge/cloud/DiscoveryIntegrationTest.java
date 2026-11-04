package io.casehub.desiredstate.example.bridge.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.api.InMemoryReconciliationStateStore;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.bridge.runtime.BridgeDiscoveryAdapter;
import io.casehub.desiredstate.bridge.runtime.BridgeManifestParser;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.DefaultDiscoveryAdapterRouter;
import io.casehub.desiredstate.runtime.DiscoveryOrchestrator;
import io.casehub.desiredstate.runtime.DiscoveryRequest;
import io.casehub.desiredstate.yaml.YamlGraphSerializer;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryIntegrationTest {

    private MockCloudProvider provider;
    private DiscoveryOrchestrator orchestrator;
    private InMemoryReconciliationStateStore stateStore;

    @BeforeEach
    void setUp() {
        provider = new MockCloudProvider();
        provider.seedResource("cloud:vm", "i-1",
            Map.of("name", "web-1", "instance_type", "t3.medium", "status", "running"));
        provider.seedResource("cloud:vm", "i-2",
            Map.of("name", "web-2", "instance_type", "t3.large", "status", "running"));
        provider.seedResource("cloud:storage", "bucket-1",
            Map.of("name", "data-bucket", "region", "us-east-1", "status", "available"));

        var descriptor = BridgeManifestParser.parse(
            getClass().getResourceAsStream("/META-INF/desiredstate/bridges/mock-cloud.yaml"));
        var discoveryAdapter = new BridgeDiscoveryAdapter(descriptor, provider);
        var router = new DefaultDiscoveryAdapterRouter(List.of(discoveryAdapter));
        stateStore = new InMemoryReconciliationStateStore();
        var graphFactory = new DefaultDesiredStateGraphFactory();
        orchestrator = new DiscoveryOrchestrator(router, graphFactory, stateStore);
    }

    @Test
    void fullDiscoveryPipeline() throws Exception {
        var result = orchestrator.discover(
            new DiscoveryRequest(Set.of(), "tenant-1", "staging", "cloud-infra"));

        assertEquals(3, result.resources().size());
        assertEquals(3, result.graph().nodes().size());

        String yaml = YamlGraphSerializer.serialize(
            "staging", "cloud-infra", result.resources());
        assertNotNull(yaml);
        assertTrue(yaml.contains("cloud:vm"));
        assertTrue(yaml.contains("cloud:storage"));

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        YamlGraph parsed = mapper.readValue(yaml, YamlGraph.class);
        assertEquals(3, parsed.nodes().size());
    }

    @Test
    void adoptionBaselinePreventsDiffOnFirstReconciliation() {
        var result = orchestrator.discover(
            new DiscoveryRequest(Set.of(), "tenant-1", "staging", "cloud-infra"));

        assertTrue(stateStore.load("tenant-1").isEmpty());
        orchestrator.adoptBaseline("tenant-1", result.graph());
        assertTrue(stateStore.load("tenant-1").isPresent());

        var stored = stateStore.load("tenant-1").get();
        assertEquals(result.graph().nodes().size(), stored.nodes().size());
    }

    @Test
    void filterByType() {
        var result = orchestrator.discover(
            new DiscoveryRequest(Set.of(NodeType.of("cloud:vm")),
                "tenant-1", "staging", "vms-only"));

        assertEquals(2, result.resources().size());
        assertTrue(result.resources().stream()
            .allMatch(r -> r.type().equals(NodeType.of("cloud:vm"))));
    }
}
