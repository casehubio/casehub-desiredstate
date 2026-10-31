package io.casehub.desiredstate.bridge.ansible;

import io.casehub.desiredstate.bridge.BridgeErrorCategory;
import io.casehub.desiredstate.bridge.BridgeImportResult;
import io.casehub.desiredstate.bridge.BridgeReadRequest;
import io.casehub.desiredstate.bridge.BridgeRequest;
import io.casehub.desiredstate.bridge.BridgeResult;
import io.casehub.desiredstate.bridge.BridgeStateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnsibleBridgeProviderTest {

    private static final String SUCCESS_JSON = """
        {"plays":[{"play":{"name":"target"},"tasks":[{"task":{"name":"provision"},
        "hosts":{"target":{"changed":true,"msg":"",
        "instances":[{"instance_id":"i-123","public_ip_address":"54.1.2.3",
        "instance_type":"t3.micro"}]}}}]}],
        "stats":{"target":{"ok":1,"changed":1,"unreachable":0,"failed":0}}}""";

    private static final String FAILED_JSON = """
        {"plays":[{"play":{"name":"target"},"tasks":[{"task":{"name":"provision"},
        "hosts":{"target":{"changed":false,"failed":true,
        "msg":"Access denied: unauthorized"}}}]}],
        "stats":{"target":{"ok":0,"changed":0,"unreachable":0,"failed":1}}}""";

    private static final String INFO_JSON = """
        {"plays":[{"play":{"name":"target"},"tasks":[{"task":{"name":"read_state"},
        "hosts":{"target":{"changed":false,"msg":"",
        "instances":[{"instance_id":"i-123","instance_type":"t3.micro",
        "security_groups":[{"group_id":"sg-abc"}]}]}}}]}],
        "stats":{"target":{"ok":1,"changed":0,"unreachable":0,"failed":0}}}""";

    private static final String EMPTY_INFO_JSON = """
        {"plays":[{"play":{"name":"target"},"tasks":[{"task":{"name":"read_state"},
        "hosts":{"target":{"changed":false,"msg":"","instances":[]}}}]}],
        "stats":{"target":{"ok":1,"changed":0,"unreachable":0,"failed":0}}}""";

    private Map<String, AnsibleModuleConfig> configs;
    private String capturedStdout;
    private AnsibleBridgeProvider provider;

    @BeforeEach
    void setUp() {
        configs = Map.of("ansible:amazon.aws.ec2_instance",
            new AnsibleModuleConfig("amazon.aws", "ec2_instance", "state_absent",
                null, "ec2_instance_info", "instance_ids", "instances",
                List.of("instance_id", "public_ip_address", "instance_type"),
                List.of("instance_type")));
        capturedStdout = SUCCESS_JSON;
        provider = new AnsibleBridgeProvider(configs, "ansible-playbook",
            (workDir, cmd, env, timeout) ->
                new AnsibleProcessResult(0, capturedStdout, ""));
    }

    @Test
    void protocolIdIsAnsible() {
        assertThat(provider.protocolId()).isEqualTo("ansible");
    }

    @Test
    void supportedTypesReturnsConfigKeys() {
        assertThat(provider.supportedTypes())
            .containsExactly("ansible:amazon.aws.ec2_instance");
    }

    @Test
    void applySuccessReturnsOutputState() {
        var request = new BridgeRequest("ansible:amazon.aws.ec2_instance", "web-server-1",
            Map.of("instance_type", "t3.micro", "image_id", "ami-123"), "tenant-1");
        var result = provider.apply(request);

        assertThat(result).isInstanceOf(BridgeResult.Success.class);
        var success = (BridgeResult.Success) result;
        assertThat(success.outputState()).containsEntry("instance_id", "i-123");
    }

    @Test
    void applyFailureReturnsFailedWithCategory() {
        provider = new AnsibleBridgeProvider(configs, "ansible-playbook",
            (workDir, cmd, env, timeout) ->
                new AnsibleProcessResult(2, FAILED_JSON, ""));

        var request = new BridgeRequest("ansible:amazon.aws.ec2_instance", "web-server-1",
            Map.of("instance_type", "t3.micro"), "tenant-1");
        var result = provider.apply(request);

        assertThat(result).isInstanceOf(BridgeResult.Failed.class);
        var failed = (BridgeResult.Failed) result;
        assertThat(failed.category()).isEqualTo(BridgeErrorCategory.AUTHENTICATION);
    }

    @Test
    void destroyInvokesWithStateAbsent() {
        var request = new BridgeRequest("ansible:amazon.aws.ec2_instance", "web-server-1",
            Map.of("instance_type", "t3.micro"), "tenant-1");
        var result = provider.destroy(request);
        assertThat(result).isInstanceOf(BridgeResult.Success.class);
    }

    @Test
    void readStateFoundReturnsPresentWhenNoDrift() {
        provider = new AnsibleBridgeProvider(configs, "ansible-playbook",
            (workDir, cmd, env, timeout) ->
                new AnsibleProcessResult(0, INFO_JSON, ""));

        var request = new BridgeReadRequest("ansible:amazon.aws.ec2_instance", "web-server-1",
            "tenant-1", Map.of("instance_id", "i-123"),
            Map.of("instance_type", "t3.micro"));
        var result = provider.readState(request);

        assertThat(result).isInstanceOf(BridgeStateResult.Found.class);
        var found = (BridgeStateResult.Found) result;
        assertThat(found.state().get("status")).isEqualTo("present");
    }

    @Test
    void readStateFoundReturnsDriftedWhenAttributesDiffer() {
        provider = new AnsibleBridgeProvider(configs, "ansible-playbook",
            (workDir, cmd, env, timeout) ->
                new AnsibleProcessResult(0, INFO_JSON, ""));

        var request = new BridgeReadRequest("ansible:amazon.aws.ec2_instance", "web-server-1",
            "tenant-1", Map.of("instance_id", "i-123"),
            Map.of("instance_type", "t3.large"));
        var result = provider.readState(request);

        assertThat(result).isInstanceOf(BridgeStateResult.Found.class);
        var found = (BridgeStateResult.Found) result;
        assertThat(found.state().get("status")).isEqualTo("drifted");
    }

    @Test
    void readStateNotFoundWhenEmptyResults() {
        provider = new AnsibleBridgeProvider(configs, "ansible-playbook",
            (workDir, cmd, env, timeout) ->
                new AnsibleProcessResult(0, EMPTY_INFO_JSON, ""));

        var request = new BridgeReadRequest("ansible:amazon.aws.ec2_instance", "web-server-1",
            "tenant-1", Map.of("instance_id", "i-999"), Map.of());
        var result = provider.readState(request);

        assertThat(result).isInstanceOf(BridgeStateResult.NotFound.class);
    }

    @Test
    void importResourceSuccess() {
        provider = new AnsibleBridgeProvider(configs, "ansible-playbook",
            (workDir, cmd, env, timeout) ->
                new AnsibleProcessResult(0, INFO_JSON, ""));

        var result = provider.importResource(
            "ansible:amazon.aws.ec2_instance", "i-123", "tenant-1");

        assertThat(result).isInstanceOf(BridgeImportResult.Success.class);
    }

    @Test
    void planReturnsBridgePlan() {
        var request = new BridgeRequest("ansible:amazon.aws.ec2_instance", "web-server-1",
            Map.of("instance_type", "t3.micro"), "tenant-1");
        var plan = provider.plan(request);

        assertThat(plan).isNotNull();
        assertThat(plan.planId()).isEqualTo("web-server-1");
    }
}
