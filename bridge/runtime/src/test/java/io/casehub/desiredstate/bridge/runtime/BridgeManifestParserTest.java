package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.bridge.BridgeDescriptor;
import io.casehub.desiredstate.bridge.BridgeErrorCategory;
import io.casehub.desiredstate.bridge.LifecycleMode;
import io.casehub.desiredstate.bridge.RestartPolicyType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeManifestParserTest {

    @Test
    void parsesProtocolAndVersion() {
        var descriptor = parseTestManifest();
        assertEquals("test-provider", descriptor.protocolId());
    }

    @Test
    void parsesHandledTypes() {
        var descriptor = parseTestManifest();
        assertEquals(2, descriptor.handledTypes().size());
        assertTrue(descriptor.handledTypes().contains(NodeType.of("test_resource")));
        assertTrue(descriptor.handledTypes().contains(NodeType.of("test_bucket")));
    }

    @Test
    void parsesStateMapping() {
        var descriptor = parseTestManifest();
        var typeDesc = descriptor.typeDescriptors().get("test_resource");
        assertEquals(NodeStatus.PRESENT, typeDesc.stateMapping().get("active"));
        assertEquals(NodeStatus.ABSENT, typeDesc.stateMapping().get("deleted"));
        assertEquals(NodeStatus.DRIFTED, typeDesc.defaultState());
    }

    @Test
    void parsesDefaultNotFoundState() {
        var descriptor = parseTestManifest();
        var typeDesc = descriptor.typeDescriptors().get("test_bucket");
        assertEquals(NodeStatus.ABSENT, typeDesc.notFoundState());
    }

    @Test
    void parsesErrorMapping() {
        var descriptor = parseTestManifest();
        var typeDesc = descriptor.typeDescriptors().get("test_resource");
        assertEquals(BridgeErrorCategory.PERMANENT, typeDesc.errorMapping().get("NotFound"));
        assertEquals(BridgeErrorCategory.TRANSIENT, typeDesc.errorMapping().get("Throttled"));
        assertEquals(BridgeErrorCategory.PERMANENT, typeDesc.defaultErrorCategory());
    }

    @Test
    void parsesLifecycleConfig() {
        var descriptor = parseTestManifest();
        var lc = descriptor.lifecycleConfig();
        assertEquals(LifecycleMode.MANAGED_PROCESS, lc.mode());
        assertEquals("/usr/bin/test-provider", lc.binaryPath());
        assertEquals(RestartPolicyType.ON_FAILURE, lc.restartPolicy().policy());
        assertEquals(3, lc.restartPolicy().maxRetries());
    }


    @Test
    void parsesCredentialRefFromManifest() {
        String yaml = """
                      bridge:
                        protocol: terraform
                        credentialRef: my-aws-creds
                        types:
                          - id: aws_instance
                            stateMapping:
                              _default: PRESENT
                            errorMapping:
                              _default: { category: PERMANENT }
                      """;
        var descriptor = BridgeManifestParser.parse(
                new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals("my-aws-creds", descriptor.credentialRef());
    }

    @Test
    void credentialRefIsNullWhenAbsent() {
        var descriptor = parseTestManifest();
        assertNull(descriptor.credentialRef());
    }

    @Test
    void parsePoolConfigFromManifest() {
        String yaml = """
                      bridge:
                        protocol: terraform
                        lifecycle:
                          mode: managed-process
                          binary: /opt/tf
                          pool:
                            maxProcesses: 5
                            idleTimeout: 10m
                            checkoutTimeout: 60s
                        types:
                          - id: "tf:test:resource"
                      """;
        var descriptor = BridgeManifestParser.parse(
                new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var pool = descriptor.lifecycleConfig().poolConfig();
        assertNotNull(pool);
        assertEquals(5, pool.maxProcesses());
        assertEquals(java.time.Duration.ofMinutes(10), pool.idleTimeout());
        assertEquals(java.time.Duration.ofSeconds(60), pool.checkoutTimeout());
    }

    @Test
    void omittedPoolConfigIsNull() {
        String yaml = """
                      bridge:
                        protocol: terraform
                        lifecycle:
                          mode: managed-process
                          binary: /opt/tf
                        types:
                          - id: "tf:test:resource"
                      """;
        var descriptor = BridgeManifestParser.parse(
                new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertNull(descriptor.lifecycleConfig().poolConfig());
    }


    private BridgeDescriptor parseTestManifest() {
        InputStream is = getClass().getResourceAsStream(
            "/META-INF/desiredstate/bridges/test-bridge.yaml");
        assertNotNull(is, "test manifest not found");
        return BridgeManifestParser.parse(is);
    }
}
