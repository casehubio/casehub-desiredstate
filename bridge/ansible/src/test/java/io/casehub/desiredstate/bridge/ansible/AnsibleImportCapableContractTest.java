package io.casehub.desiredstate.bridge.ansible;

import io.casehub.desiredstate.bridge.ImportCapable;
import io.casehub.desiredstate.bridge.testing.ImportCapableContractTest;

import java.util.List;
import java.util.Map;

class AnsibleImportCapableContractTest extends ImportCapableContractTest {

    private static final String INFO_JSON = """
        {"plays":[{"play":{"name":"target"},"tasks":[{"task":{"name":"read_state"},
        "hosts":{"target":{"changed":false,"msg":"",
        "instances":[{"instance_id":"i-123","instance_type":"t3.micro"}]}}}]}],
        "stats":{"target":{"ok":1,"changed":0,"unreachable":0,"failed":0}}}""";

    @Override
    protected ImportCapable createImportCapableProvider() {
        return new AnsibleBridgeProvider(
            Map.of("ansible:test.collection.resource",
                new AnsibleModuleConfig("test.collection", "resource", "state_absent",
                    null, "resource_info", "resource_ids", "instances",
                    List.of("instance_id"), List.of())),
            "ansible-playbook",
            (workDir, cmd, env, timeout) -> new AnsibleProcessResult(0, INFO_JSON, ""));
    }

    @Override
    protected String sampleTypeId() {
        return "ansible:test.collection.resource";
    }

    @Override
    protected String sampleImportId() {
        return "i-123";
    }

    @Override
    protected String sampleTenancyId() {
        return "t-1";
    }
}
