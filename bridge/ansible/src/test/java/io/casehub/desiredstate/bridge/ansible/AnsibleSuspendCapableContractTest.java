package io.casehub.desiredstate.bridge.ansible;

import io.casehub.desiredstate.bridge.BridgeRequest;
import io.casehub.desiredstate.bridge.BridgeResult;
import io.casehub.desiredstate.bridge.SuspendCapable;
import io.casehub.desiredstate.bridge.testing.SuspendCapableContractTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnsibleSuspendCapableContractTest extends SuspendCapableContractTest {

    private static final String SUCCESS_JSON = """
        {"plays":[{"play":{"name":"target"},"tasks":[{"task":{"name":"suspend"},
        "hosts":{"target":{"changed":true,"msg":""}}}]}],
        "stats":{"target":{"ok":1,"changed":1,"unreachable":0,"failed":0}}}""";

    @Override
    protected SuspendCapable createSuspendCapableProvider() {
        return new AnsibleBridgeProvider(
            Map.of("ansible:test.collection.resource",
                new AnsibleModuleConfig("test.collection", "resource", "state_absent",
                    null, null, null, null, List.of(), List.of(),
                    "resource", "state", "stopped", null, null, "running")),
            "ansible-playbook",
            (workDir, cmd, env, timeout) -> new AnsibleProcessResult(0, SUCCESS_JSON, ""));
    }

    @Override
    protected BridgeRequest sampleSuspendRequest() {
        return new BridgeRequest("ansible:test.collection.resource", "r-1",
            Map.of("param1", "val1"), "t-1");
    }

    @Test
    void suspendReturnsFailedForUnsupportedType() {
        var provider = new AnsibleBridgeProvider(
            Map.of("ansible:test.collection.resource",
                new AnsibleModuleConfig("test.collection", "resource", "state_absent",
                    null, null, null, null, List.of(), List.of(),
                    null, null, null, null, null, null)),
            "ansible-playbook",
            (workDir, cmd, env, timeout) -> new AnsibleProcessResult(0, "{}", ""));

        var request = new BridgeRequest("ansible:test.collection.resource", "r-1",
            Map.of(), "t-1");
        var result = ((SuspendCapable) provider).suspend(request);

        assertThat(result).isInstanceOf(BridgeResult.Failed.class);
        assertThat(((BridgeResult.Failed) result).reason()).contains("Suspend not supported");
    }
}
