package io.casehub.desiredstate.bridge.ansible;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnsibleManifestExtensionParserTest {

    @Test
    void parsesEc2InstanceConfig() {
        var configs = AnsibleManifestExtensionParser.parse(
            getClass().getResourceAsStream("/META-INF/desiredstate/bridges/test-ansible.yaml"));

        assertThat(configs).containsKey("ansible:amazon.aws.ec2_instance");
        var ec2 = configs.get("ansible:amazon.aws.ec2_instance");
        assertThat(ec2.collection()).isEqualTo("amazon.aws");
        assertThat(ec2.provisionModule()).isEqualTo("ec2_instance");
        assertThat(ec2.deprovisionStrategy()).isEqualTo("state_absent");
        assertThat(ec2.infoModule()).isEqualTo("ec2_instance_info");
        assertThat(ec2.infoFilterKey()).isEqualTo("instance_ids");
        assertThat(ec2.resultKey()).isEqualTo("instances");
        assertThat(ec2.outputKeys()).containsExactly("instance_id", "public_ip_address", "instance_type");
        assertThat(ec2.driftKeys()).containsExactly("instance_type", "security_groups");
    }

    @Test
    void parsesS3BucketConfig() {
        var configs = AnsibleManifestExtensionParser.parse(
            getClass().getResourceAsStream("/META-INF/desiredstate/bridges/test-ansible.yaml"));

        assertThat(configs).containsKey("ansible:amazon.aws.s3_bucket");
        var s3 = configs.get("ansible:amazon.aws.s3_bucket");
        assertThat(s3.provisionModule()).isEqualTo("s3_bucket");
        assertThat(s3.deprovisionStrategy()).isEqualTo("state_absent");
    }

    @Test
    void parsesMultipleTypes() {
        var configs = AnsibleManifestExtensionParser.parse(
            getClass().getResourceAsStream("/META-INF/desiredstate/bridges/test-ansible.yaml"));

        assertThat(configs).hasSize(2);
    }

    @Test
    void defaultsDeprovisionStrategyToStateAbsent() {
        var configs = AnsibleManifestExtensionParser.parse(
            getClass().getResourceAsStream("/META-INF/desiredstate/bridges/test-ansible.yaml"));

        var ec2 = configs.get("ansible:amazon.aws.ec2_instance");
        assertThat(ec2.deprovisionStrategy()).isEqualTo("state_absent");
        assertThat(ec2.deprovisionModule()).isNull();
    }

    @Test
    void parsesSuspendResumeConfig() {
        var configs = AnsibleManifestExtensionParser.parse(
                getClass().getResourceAsStream("/META-INF/desiredstate/bridges/test-ansible.yaml"));

        var ec2 = configs.get("ansible:amazon.aws.ec2_instance");
        assertThat(ec2.suspendModule()).isEqualTo("ec2_instance");
        assertThat(ec2.suspendStateParam()).isEqualTo("state");
        assertThat(ec2.suspendStateValue()).isEqualTo("stopped");
        assertThat(ec2.resumeModule()).isNull();
        assertThat(ec2.resumeStateParam()).isNull();
        assertThat(ec2.resumeStateValue()).isEqualTo("running");
        assertThat(ec2.supportsSuspend()).isTrue();
        assertThat(ec2.fqSuspendModule()).isEqualTo("amazon.aws.ec2_instance");
    }

    @Test
    void typeWithoutSuspendConfigReturnsFalseForSupportsSuspend() {
        var configs = AnsibleManifestExtensionParser.parse(
                getClass().getResourceAsStream("/META-INF/desiredstate/bridges/test-ansible.yaml"));

        var s3 = configs.get("ansible:amazon.aws.s3_bucket");
        assertThat(s3.supportsSuspend()).isFalse();
        assertThat(s3.suspendModule()).isNull();
        assertThat(s3.fqSuspendModule()).isNull();
    }

    @Test
    void resumeModuleDefaultsToSuspendModule() {
        var configs = AnsibleManifestExtensionParser.parse(
                getClass().getResourceAsStream("/META-INF/desiredstate/bridges/test-ansible.yaml"));

        var ec2 = configs.get("ansible:amazon.aws.ec2_instance");
        assertThat(ec2.effectiveResumeModule()).isEqualTo("ec2_instance");
        assertThat(ec2.fqResumeModule()).isEqualTo("amazon.aws.ec2_instance");
        assertThat(ec2.effectiveResumeStateParam()).isEqualTo("state");
    }
}
