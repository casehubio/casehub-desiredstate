package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.bridge.*;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.*;

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

    private BridgeDescriptor parseTestManifest() {
        InputStream is = getClass().getResourceAsStream(
            "/META-INF/desiredstate/bridges/test-bridge.yaml");
        assertNotNull(is, "test manifest not found");
        return BridgeManifestParser.parse(is);
    }
}
