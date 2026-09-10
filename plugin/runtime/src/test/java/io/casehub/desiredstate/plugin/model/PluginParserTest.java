package io.casehub.desiredstate.plugin.model;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginParserTest {

    @Test
    void parsesPluginHeader() throws IOException {
        var model = parseTestResource();
        assertThat(model.header().type()).isEqualTo("test-resource");
        assertThat(model.header().version()).isEqualTo(1);
        assertThat(model.header().resyncInterval()).isEqualTo("30s");
        assertThat(model.header().auth()).containsKey("test-api");
        assertThat(model.header().auth().get("test-api").credentialRef())
            .isEqualTo("test-api-credentials");
    }

    @Test
    void parsesSpecSchema() throws IOException {
        var model = parseTestResource();
        assertThat(model.spec().fields()).containsKeys(
            "name", "count", "enabled", "mode", "tags", "metadata");

        var nameField = model.spec().fields().get("name");
        assertThat(nameField.type()).isEqualTo("string");
        assertThat(nameField.required()).isTrue();

        var countField = model.spec().fields().get("count");
        assertThat(countField.type()).isEqualTo("integer");
        assertThat(countField.defaultValue()).isEqualTo(1);
        assertThat(countField.min()).isEqualTo(0);
        assertThat(countField.max()).isEqualTo(100);

        var enabledField = model.spec().fields().get("enabled");
        assertThat(enabledField.type()).isEqualTo("boolean");
        assertThat(enabledField.defaultValue()).isEqualTo(true);

        var modeField = model.spec().fields().get("mode");
        assertThat(modeField.type()).isEqualTo("enum");
        assertThat(modeField.values()).containsExactly("fast", "slow", "balanced");
        assertThat(modeField.defaultValue()).isEqualTo("balanced");

        var tagsField = model.spec().fields().get("tags");
        assertThat(tagsField.type()).isEqualTo("list");
        assertThat(tagsField.itemType()).isEqualTo("string");

        var metadataField = model.spec().fields().get("metadata");
        assertThat(metadataField.type()).isEqualTo("map");
        assertThat(metadataField.valueType()).isEqualTo("string");
    }

    @Test
    void parsesActualStateSteps() throws IOException {
        var model = parseTestResource();
        assertThat(model.actualStateSteps()).hasSize(2);

        var restCall = model.actualStateSteps().get(0);
        assertThat(restCall.primitiveName()).isEqualTo("rest-call");
        assertThat(restCall.parameters().get("method")).isEqualTo("GET");
        assertThat(restCall.parameters().get("url"))
            .isEqualTo("https://${auth.test-api.endpoint}/resources/${spec.name}");
        assertThat(restCall.parameters().get("auth")).isEqualTo("test-api");
        assertThat(restCall.resultName()).isEqualTo("response");

        var compareState = model.actualStateSteps().get(1);
        assertThat(compareState.primitiveName()).isEqualTo("compare-state");
        assertThat(compareState.parameters().get("present-when"))
            .isEqualTo("${result.response.status} == 200");
        assertThat(compareState.parameters().get("drifted-when"))
            .isEqualTo("${result.response.body.count} < ${spec.count}");
        assertThat(compareState.parameters().get("absent-when"))
            .isEqualTo("${result.response.status} == 404");
    }

    @Test
    void parsesProvisionerSteps() throws IOException {
        var model = parseTestResource();
        assertThat(model.provisioner().provisionSteps()).hasSize(2);
        assertThat(model.provisioner().deprovisionSteps()).hasSize(2);

        var putCall = model.provisioner().provisionSteps().get(0);
        assertThat(putCall.primitiveName()).isEqualTo("rest-call");
        assertThat(putCall.parameters().get("method")).isEqualTo("PUT");
        assertThat(putCall.resultName()).isEqualTo("response");
        assertThat(putCall.parameters()).containsKey("headers");
        assertThat(putCall.parameters()).containsKey("body");

        var assertion = model.provisioner().provisionSteps().get(1);
        assertThat(assertion.primitiveName()).isEqualTo("assert");
        assertThat(assertion.parameters().get("condition"))
            .isEqualTo("${result.response.status} in [200, 201]");
        assertThat(assertion.parameters().get("message"))
            .isEqualTo("Provision failed: HTTP ${result.response.status}");
    }

    @Test
    void stepDirectivesNotInParameters() throws IOException {
        var model = parseTestResource();
        var restCall = model.actualStateSteps().get(0);
        assertThat(restCall.parameters()).doesNotContainKey("result");
    }

    @Test
    void parsesFaultPolicy() throws IOException {
        var model = parseTestResource();
        assertThat(model.faultPolicies()).hasSize(1);

        var policy = model.faultPolicies().get(0);
        assertThat(policy.faultTypes()).containsExactly("PROVISION_FAILED");
        assertThat(policy.nodeTypes()).containsExactly("test-resource");
        assertThat(policy.namespace()).isEqualTo("test-resource-escalation");
        assertThat(policy.tiers()).hasSize(1);
        assertThat(policy.tiers().get(0).threshold()).isEqualTo(3);
        assertThat(policy.tiers().get(0).reviewNode().type()).isEqualTo("human-review");
        assertThat(policy.tiers().get(0).reviewNode().humanGating()).isEqualTo("ALL");
        assertThat(policy.tiers().get(0).reviewNode().spec())
            .containsEntry("target", "${fault.nodeId}");
    }

    @Test
    void parsesCbrSection() throws IOException {
        var model = parseTestResource();
        assertThat(model.cbr().features()).hasSize(2);

        var countFeature = model.cbr().features().get(0);
        assertThat(countFeature.name()).isEqualTo("count");
        assertThat(countFeature.source()).isEqualTo("spec.count");
        assertThat(countFeature.similarity()).isEqualTo("numeric");
        assertThat(countFeature.transform()).isNull();

        var modeFeature = model.cbr().features().get(1);
        assertThat(modeFeature.name()).isEqualTo("mode");
        assertThat(modeFeature.similarity()).isEqualTo("categorical");

        assertThat(model.cbr().outcomeSignals()).containsKey("success");
        assertThat(model.cbr().outcomeSignals().get("success"))
            .isEqualTo("${result.response.body.count} >= ${spec.count}");
    }

    @Test
    void parsesRasSection() throws IOException {
        var model = parseTestResource();
        assertThat(model.ras().situations()).hasSize(1);

        var sit = model.ras().situations().get(0);
        assertThat(sit.name()).isEqualTo("repeated-failures");
        assertThat(sit.events()).containsExactly("NODE_FAULTED", "NODE_RECOVERED");
        assertThat(sit.correlationWindow()).isEqualTo("10m");
        assertThat(sit.chainMode()).containsEntry("streak", 3);
        assertThat(sit.trigger()).isEqualTo("create-case");
        assertThat(sit.triggerMode()).isEqualTo("fire-once");
        assertThat(sit.correlationKey()).isEqualTo("${spec.name}");
    }

    @Test
    void faultPolicyIsOptional() throws IOException {
        var yaml = """
            plugin:
              type: minimal
              version: 1
            spec:
              fields:
                name: { type: string, required: true }
            actual-state:
              steps:
                - compare-state:
                    present-when: "true"
                    absent-when: "false"
            provisioner:
              provision:
                steps:
                  - assert:
                      condition: "true"
              deprovision:
                steps:
                  - assert:
                      condition: "true"
            cbr:
              features: []
              outcome-signals: {}
            ras:
              situations: []
            """;
        var model = PluginParser.parse(new ByteArrayInputStream(yaml.getBytes()));
        assertThat(model.faultPolicies()).isEmpty();
        assertThat(model.header().type()).isEqualTo("minimal");
        assertThat(model.header().auth()).isEmpty();
    }

    @Test
    void rejectsMissingPluginSection() {
        var yaml = """
            spec:
              fields:
                name: { type: string }
            actual-state:
              steps: []
            provisioner:
              provision:
                steps: []
              deprovision:
                steps: []
            cbr:
              features: []
              outcome-signals: {}
            ras:
              situations: []
            """;
        assertThatThrownBy(() -> PluginParser.parse(new ByteArrayInputStream(yaml.getBytes())))
            .isInstanceOf(PluginParseException.class)
            .hasMessageContaining("plugin");
    }

    @Test
    void rejectsMissingProvisionerSection() {
        var yaml = """
            plugin:
              type: incomplete
              version: 1
            spec:
              fields:
                name: { type: string }
            actual-state:
              steps: []
            cbr:
              features: []
              outcome-signals: {}
            ras:
              situations: []
            """;
        assertThatThrownBy(() -> PluginParser.parse(new ByteArrayInputStream(yaml.getBytes())))
            .isInstanceOf(PluginParseException.class)
            .hasMessageContaining("provisioner");
    }

    @Test
    void parsesStepWithControlFlowDirectives() throws IOException {
        var yaml = """
            plugin:
              type: with-control
              version: 1
            spec:
              fields:
                name: { type: string }
            actual-state:
              steps:
                - rest-call:
                    method: GET
                    url: "https://example.com"
                    result: r
                    when: "${spec.name} != null"
                    on-error: retry
                    max-retries: 5
                    backoff: "exponential:1s"
                - compare-state:
                    present-when: "${result.r.status} == 200"
                    absent-when: "${result.r.status} == 404"
            provisioner:
              provision:
                steps:
                  - assert:
                      condition: "true"
              deprovision:
                steps:
                  - assert:
                      condition: "true"
            cbr:
              features: []
              outcome-signals: {}
            ras:
              situations: []
            """;
        var model = PluginParser.parse(new ByteArrayInputStream(yaml.getBytes()));
        var step = model.actualStateSteps().get(0);
        assertThat(step.when()).isEqualTo("${spec.name} != null");
        assertThat(step.onError()).isEqualTo("retry");
        assertThat(step.maxRetries()).isEqualTo(5);
        assertThat(step.backoff()).isEqualTo("exponential:1s");
        assertThat(step.parameters()).doesNotContainKeys(
            "result", "when", "on-error", "max-retries", "backoff");
    }

    private PluginModel parseTestResource() throws IOException {
        try (var is = getClass().getResourceAsStream(
                "/META-INF/desiredstate/plugins/test-resource.yaml")) {
            return PluginParser.parse(is);
        }
    }
}
