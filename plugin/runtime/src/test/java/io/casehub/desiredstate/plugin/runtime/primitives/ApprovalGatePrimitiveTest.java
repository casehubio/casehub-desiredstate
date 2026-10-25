package io.casehub.desiredstate.plugin.runtime.primitives;

import io.casehub.yaml.plugin.api.MapServiceRegistry;
import io.casehub.yaml.plugin.api.Result;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalGatePrimitiveTest {

    private final ApprovalGatePrimitive primitive = new ApprovalGatePrimitive();

    @Test
    void returnsPendingStatusWithReason() {
        var result = primitive.execute(
                Map.of("reason", "production deployment"),
                new MapServiceRegistry());

        assertThat(result).isInstanceOf(Result.Success.class);
        assertThat(result.output()).containsEntry("status", "pending");
        assertThat(result.output()).containsEntry("reason", "production deployment");
    }

    @Test
    void includesApproverWhenSpecified() {
        var result = primitive.execute(
                Map.of("reason", "deploy", "approver", "ops-team"),
                new MapServiceRegistry());

        assertThat(result.output()).containsEntry("approver", "ops-team");
    }

    @Test
    void usesDefaultReasonWhenNotSpecified() {
        var result = primitive.execute(Map.of(), new MapServiceRegistry());

        assertThat(result.output()).containsEntry("reason", "approval required");
        assertThat(result.output()).containsEntry("status", "pending");
    }

    @Test
    void includesGateNameWhenSpecified() {
        var result = primitive.execute(
                Map.of("name", "prod-gate"),
                new MapServiceRegistry());

        assertThat(result.output()).containsEntry("gateName", "prod-gate");
    }
}
