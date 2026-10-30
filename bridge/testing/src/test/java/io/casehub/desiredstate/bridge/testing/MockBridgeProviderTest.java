package io.casehub.desiredstate.bridge.testing;

import io.casehub.desiredstate.bridge.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class MockBridgeProviderTest {

    @Test
    void defaultApplyReturnsSuccess() {
        var mock = new MockBridgeProvider("test-proto", "res_a");
        var result = mock.apply(new BridgeRequest("res_a", "node-1", Map.of(), "t1"));
        assertThat(result).isInstanceOf(BridgeResult.Success.class);
    }

    @Test
    void failNextNReturnsFailureThenReverts() {
        var mock = new MockBridgeProvider("test-proto", "res_a");
        var failResult = new BridgeResult.Failed("boom", BridgeErrorCategory.TRANSIENT);
        mock.failNextN(2, failResult);

        var req = new BridgeRequest("res_a", "node-1", Map.of(), "t1");
        assertThat(mock.apply(req)).isInstanceOf(BridgeResult.Failed.class);
        assertThat(mock.apply(req)).isInstanceOf(BridgeResult.Failed.class);
        assertThat(mock.apply(req)).isInstanceOf(BridgeResult.Success.class);
    }

    @Test
    void failNextNDestroyReturnsFailureThenReverts() {
        var mock = new MockBridgeProvider("test-proto", "res_a");
        var failResult = new BridgeResult.Failed("crash", BridgeErrorCategory.PERMANENT);
        mock.failNextNDestroy(1, failResult);

        var req = new BridgeRequest("res_a", "node-1", Map.of(), "t1");
        assertThat(mock.destroy(req)).isInstanceOf(BridgeResult.Failed.class);
        assertThat(mock.destroy(req)).isInstanceOf(BridgeResult.Success.class);
    }

    @Test
    void callsAreRecorded() {
        var mock = new MockBridgeProvider("test-proto", "res_a");
        var req = new BridgeRequest("res_a", "node-1", Map.of("key", "val"), "t1");
        mock.apply(req);
        mock.destroy(req);
        mock.readState(new BridgeReadRequest("res_a", "node-1", "t1"));

        assertThat(mock.applyCalls()).hasSize(1);
        assertThat(mock.destroyCalls()).hasSize(1);
        assertThat(mock.readCalls()).hasSize(1);
        assertThat(mock.applyCalls().getFirst().spec()).containsEntry("key", "val");
    }

    @Test
    void protocolIdAndSupportedTypes() {
        var mock = new MockBridgeProvider("my-proto", "type_a", "type_b");
        assertThat(mock.protocolId()).isEqualTo("my-proto");
        assertThat(mock.supportedTypes()).containsExactlyInAnyOrder("type_a", "type_b");
    }
}
