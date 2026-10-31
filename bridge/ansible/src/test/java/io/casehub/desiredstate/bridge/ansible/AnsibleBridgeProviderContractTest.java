package io.casehub.desiredstate.bridge.ansible;

import io.casehub.desiredstate.bridge.BridgeProvider;
import io.casehub.desiredstate.bridge.BridgeReadRequest;
import io.casehub.desiredstate.bridge.BridgeRequest;
import io.casehub.desiredstate.bridge.testing.BridgeProviderContractTest;

import java.util.List;
import java.util.Map;
import java.util.Set;

class AnsibleBridgeProviderContractTest extends BridgeProviderContractTest {

    private static final String SUCCESS_JSON = """
        {"plays":[{"play":{"name":"target"},"tasks":[{"task":{"name":"provision"},
        "hosts":{"target":{"changed":true,"msg":"",
        "instances":[{"instance_id":"i-123","instance_type":"t3.micro"}]}}}]}],
        "stats":{"target":{"ok":1,"changed":1,"unreachable":0,"failed":0}}}""";

    @Override
    protected BridgeProvider createProvider() {
        return new AnsibleBridgeProvider(
            Map.of("ansible:test.collection.resource",
                new AnsibleModuleConfig("test.collection", "resource", "state_absent",
                    null, "resource_info", "resource_ids", "instances",
                    List.of("instance_id", "instance_type"), List.of())),
            "ansible-playbook",
            (workDir, cmd, env, timeout) -> new AnsibleProcessResult(0, SUCCESS_JSON, ""));
    }

    @Override
    protected String expectedProtocolId() {
        return "ansible";
    }

    @Override
    protected Set<String> expectedSupportedTypes() {
        return Set.of("ansible:test.collection.resource");
    }

    @Override
    protected BridgeRequest sampleApplyRequest() {
        return new BridgeRequest("ansible:test.collection.resource", "r-1",
            Map.of("param1", "val1"), "t-1");
    }

    @Override
    protected BridgeRequest sampleDestroyRequest() {
        return sampleApplyRequest();
    }

    @Override
    protected BridgeReadRequest sampleReadRequest() {
        return new BridgeReadRequest("ansible:test.collection.resource", "r-1", "t-1",
            Map.of("instance_id", "i-123"), Map.of());
    }
}
