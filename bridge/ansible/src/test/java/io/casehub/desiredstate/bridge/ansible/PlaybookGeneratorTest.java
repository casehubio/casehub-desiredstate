package io.casehub.desiredstate.bridge.ansible;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybookGeneratorTest {

    @Test
    void provisionPlaybookContainsModuleAndParams() {
        String yaml = PlaybookGenerator.provision(
            "amazon.aws.ec2_instance",
            Map.of("instance_type", "t3.micro", "image_id", "ami-123"));

        assertThat(yaml).contains("amazon.aws.ec2_instance:");
        assertThat(yaml).contains("instance_type:");
        assertThat(yaml).contains("t3.micro");
        assertThat(yaml).contains("image_id:");
        assertThat(yaml).contains("ami-123");
        assertThat(yaml).contains("gather_facts: false");
        assertThat(yaml).contains("register:");
    }

    @Test
    void deprovisionWithStateAbsentAddsStateField() {
        String yaml = PlaybookGenerator.deprovision(
            "amazon.aws.ec2_instance",
            Map.of("instance_type", "t3.micro"),
            "state_absent");

        assertThat(yaml).contains("state:");
        assertThat(yaml).contains("absent");
        assertThat(yaml).contains("amazon.aws.ec2_instance:");
    }

    @Test
    void deprovisionWithModuleUsesAlternateModule() {
        String yaml = PlaybookGenerator.deprovision(
            "amazon.aws.custom_cleanup",
            Map.of("resource_id", "r-123"),
            "deprovision_module");

        assertThat(yaml).contains("amazon.aws.custom_cleanup:");
        assertThat(yaml).doesNotContain("state: absent");
    }

    @Test
    void readStatePlaybookUsesInfoModule() {
        String yaml = PlaybookGenerator.readState(
            "amazon.aws.ec2_instance_info",
            "instance_ids",
            List.of("i-123"));

        assertThat(yaml).contains("amazon.aws.ec2_instance_info:");
        assertThat(yaml).contains("instance_ids:");
    }

    @Test
    void checkModePlaybookContainsCheckFlag() {
        String yaml = PlaybookGenerator.check(
            "amazon.aws.ec2_instance",
            Map.of("instance_type", "t3.micro"));

        assertThat(yaml).contains("check_mode: true");
        assertThat(yaml).contains("amazon.aws.ec2_instance:");
    }
}
