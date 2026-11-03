package io.casehub.desiredstate.bridge.terraform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerraformManifestExtensionParserTest {

    @Test
    void parsesSuspendConfigForEc2Instance() {
        var configs = TerraformManifestExtensionParser.parse(
            getClass().getResourceAsStream(
                "/META-INF/desiredstate/bridges/test-terraform.yaml"));

        assertThat(configs).containsKey("tf:hashicorp/aws:aws_instance");
        var config = configs.get("tf:hashicorp/aws:aws_instance");
        assertThat(config.attribute()).isEqualTo("instance_state");
        assertThat(config.suspendValue()).isEqualTo("stopped");
        assertThat(config.resumeValue()).isEqualTo("running");
    }

    @Test
    void typeWithoutTerraformBlockIsNotIncluded() {
        var configs = TerraformManifestExtensionParser.parse(
            getClass().getResourceAsStream(
                "/META-INF/desiredstate/bridges/test-terraform.yaml"));

        assertThat(configs).doesNotContainKey("tf:hashicorp/aws:aws_s3_bucket");
    }

    @Test
    void parsesMultipleSuspendableTypes() {
        var configs = TerraformManifestExtensionParser.parse(
            getClass().getResourceAsStream(
                "/META-INF/desiredstate/bridges/test-terraform.yaml"));

        assertThat(configs).containsKey("tf:hashicorp/aws:aws_rds_cluster");
        var rds = configs.get("tf:hashicorp/aws:aws_rds_cluster");
        assertThat(rds.attribute()).isEqualTo("engine_lifecycle_support");
        assertThat(rds.suspendValue()).isEqualTo("stop");
        assertThat(rds.resumeValue()).isEqualTo("start");
    }
}
