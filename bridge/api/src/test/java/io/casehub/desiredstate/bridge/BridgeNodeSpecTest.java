package io.casehub.desiredstate.bridge;

import io.casehub.desiredstate.api.NodeType;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BridgeNodeSpecTest {

    @Test
    void importIdReturnsValueFromFields() {
        var spec = new BridgeNodeSpec(NodeType.of("test"), Map.of("import", "i-abc123"));
        assertEquals("i-abc123", spec.importId());
    }

    @Test
    void importIdReturnsNullWhenAbsent() {
        var spec = new BridgeNodeSpec(NodeType.of("test"), Map.of("name", "web"));
        assertNull(spec.importId());
    }

    @Test
    void importIdReturnsNullForEmptyFields() {
        var spec = new BridgeNodeSpec(NodeType.of("test"), Map.of());
        assertNull(spec.importId());
    }
}
