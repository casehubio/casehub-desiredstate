package io.casehub.desiredstate.bridge.ansible;

import io.casehub.desiredstate.bridge.BridgeRequest;
import io.casehub.desiredstate.bridge.PlanCapable;
import io.casehub.desiredstate.bridge.testing.PlanCapableContractTest;

import java.util.List;
import java.util.Map;

class AnsiblePlanCapableContractTest extends PlanCapableContractTest {

    private static final String CHECK_JSON = """
        {"plays":[{"play":{"name":"target"},"tasks":[{"task":{"name":"check"},
        "hosts":{"target":{"changed":true,"msg":""}}}]}],
        "stats":{"target":{"ok":1,"changed":1,"unreachable":0,"failed":0}}}""";

    @Override
    protected PlanCapable createPlanCapableProvider() {
        return new AnsibleBridgeProvider(
            Map.of("ansible:test.collection.resource",
                new AnsibleModuleConfig("test.collection", "resource", "state_absent",
                    null, null, null, null, List.of(), List.of())),
            "ansible-playbook",
            (workDir, cmd, env, timeout) -> new AnsibleProcessResult(0, CHECK_JSON, ""));
    }

    @Override
    protected BridgeRequest samplePlanRequest() {
        return new BridgeRequest("ansible:test.collection.resource", "r-1",
            Map.of("param1", "val1"), "t-1");
    }
}
