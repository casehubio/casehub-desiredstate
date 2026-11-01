package io.casehub.desiredstate.bridge.ansible;

import io.casehub.desiredstate.bridge.BridgeErrorCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnsibleErrorClassifierTest {

    @Test
    void unreachableIsTransient() {
        var result = new AnsibleTaskResult("unreachable", "Connection refused", false);
        assertThat(AnsibleErrorClassifier.classify(result)).isEqualTo(BridgeErrorCategory.TRANSIENT);
    }

    @Test
    void failedWithAuthKeywordIsAuthentication() {
        var result = new AnsibleTaskResult("failed", "Access denied: unauthorized", false);
        assertThat(AnsibleErrorClassifier.classify(result)).isEqualTo(BridgeErrorCategory.AUTHENTICATION);
    }

    @Test
    void failedWithCredentialKeywordIsAuthentication() {
        var result = new AnsibleTaskResult("failed", "Invalid credential provided", false);
        assertThat(AnsibleErrorClassifier.classify(result)).isEqualTo(BridgeErrorCategory.AUTHENTICATION);
    }

    @Test
    void failedWithConfigKeywordIsConfiguration() {
        var result = new AnsibleTaskResult("failed", "missing required arguments: region", false);
        assertThat(AnsibleErrorClassifier.classify(result)).isEqualTo(BridgeErrorCategory.CONFIGURATION);
    }

    @Test
    void failedWithQuotaKeywordIsQuota() {
        var result = new AnsibleTaskResult("failed", "You have exceeded your instance limit", false);
        assertThat(AnsibleErrorClassifier.classify(result)).isEqualTo(BridgeErrorCategory.QUOTA);
    }

    @Test
    void failedWithUnrecognizedMessageDefaultsToTransient() {
        var result = new AnsibleTaskResult("failed", "Something went wrong", false);
        assertThat(AnsibleErrorClassifier.classify(result)).isEqualTo(BridgeErrorCategory.TRANSIENT);
    }

    @Test
    void timeoutIsTransient() {
        var result = new AnsibleTaskResult("timeout", "Execution timed out", false);
        assertThat(AnsibleErrorClassifier.classify(result)).isEqualTo(BridgeErrorCategory.TRANSIENT);
    }

    @Test
    void failedWithAlreadyExistsIsPermanent() {
        var result = new AnsibleTaskResult("failed", "Resource already exists: vpc-123", false);
        assertThat(AnsibleErrorClassifier.classify(result)).isEqualTo(BridgeErrorCategory.PERMANENT);
    }

    @Test
    void failedWithConflictIsPermanent() {
        var result = new AnsibleTaskResult("failed", "Conflict: resource in use by another process", false);
        assertThat(AnsibleErrorClassifier.classify(result)).isEqualTo(BridgeErrorCategory.PERMANENT);
    }

    @Test
    void failedWithNotSupportedIsPermanent() {
        var result = new AnsibleTaskResult("failed", "Operation not supported on this instance type", false);
        assertThat(AnsibleErrorClassifier.classify(result)).isEqualTo(BridgeErrorCategory.PERMANENT);
    }

    @Test
    void failedWithNotFoundIsPermanent() {
        var result = new AnsibleTaskResult("failed", "Resource not found: i-deleted", false);
        assertThat(AnsibleErrorClassifier.classify(result)).isEqualTo(BridgeErrorCategory.PERMANENT);
    }

}
