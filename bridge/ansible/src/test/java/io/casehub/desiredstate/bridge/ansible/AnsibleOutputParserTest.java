package io.casehub.desiredstate.bridge.ansible;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnsibleOutputParserTest {

    private String loadResource(String name) throws IOException {
        return new String(getClass().getResourceAsStream("/" + name).readAllBytes(),
            StandardCharsets.UTF_8);
    }

    @Test
    void parsesSuccessfulOutput() throws Exception {
        var result = AnsibleOutputParser.parse(loadResource("ansible-output-success.json"));
        assertThat(result.taskResult().status()).isEqualTo("ok");
        assertThat(result.taskResult().changed()).isTrue();
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void parsesFailedOutput() throws Exception {
        var result = AnsibleOutputParser.parse(loadResource("ansible-output-failed.json"));
        assertThat(result.taskResult().status()).isEqualTo("failed");
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void extractsOutputStateByKeys() {
        var taskResult = new AnsibleTaskResult("ok", "", true,
            Map.of("instances", List.of(Map.of(
                "instance_id", "i-123",
                "public_ip_address", "54.1.2.3",
                "instance_type", "t3.micro",
                "private_ip", "10.0.0.1"))));
        var config = new AnsibleModuleConfig("amazon.aws", "ec2_instance", "state_absent",
            null, "ec2_instance_info", "instance_ids", "instances",
            List.of("instance_id", "public_ip_address", "instance_type"), List.of());

        var outputState = AnsibleOutputParser.extractOutputState(taskResult, config);

        assertThat(outputState).containsOnlyKeys("instance_id", "public_ip_address", "instance_type");
        assertThat(outputState.get("instance_id")).isEqualTo("i-123");
    }

    @Test
    void detectsDriftWhenKeysDiffer() {
        var actual = Map.<String, Object>of("instance_type", "t3.large", "tags", Map.of());
        var desired = Map.<String, Object>of("instance_type", "t3.micro", "tags", Map.of());
        assertThat(AnsibleOutputParser.detectDrift(actual, desired,
            List.of("instance_type"))).isTrue();
    }

    @Test
    void noDriftWhenKeysMatch() {
        var actual = Map.<String, Object>of("instance_type", "t3.micro");
        var desired = Map.<String, Object>of("instance_type", "t3.micro");
        assertThat(AnsibleOutputParser.detectDrift(actual, desired,
            List.of("instance_type"))).isFalse();
    }

    @Test
    void noDriftWhenDriftKeysEmpty() {
        var actual = Map.<String, Object>of("instance_type", "t3.large");
        var desired = Map.<String, Object>of("instance_type", "t3.micro");
        assertThat(AnsibleOutputParser.detectDrift(actual, desired, List.of())).isFalse();
    }

    @Test
    void extractOutputStateWithNullResultKeyUsesRawResult() {
        var taskResult = new AnsibleTaskResult("ok", "", true,
            Map.of("instance_id", "i-123", "instance_type", "t3.micro"));
        var config = new AnsibleModuleConfig("amazon.aws", "ec2_instance", "state_absent",
            null, null, null, null,
            List.of("instance_id"), List.of());

        var outputState = AnsibleOutputParser.extractOutputState(taskResult, config);

        assertThat(outputState).containsEntry("instance_id", "i-123");
    }
}
