package io.casehub.desiredstate.bridge.terraform;

import io.casehub.desiredstate.bridge.BridgeErrorCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TerraformErrorClassifierTest {

    @Test
    void authenticationError() {
        var diag = new TfDiagnostic(TfDiagnostic.Severity.ERROR, "AuthFailure", "Invalid credentials");
        assertThat(TerraformErrorClassifier.classify(List.of(diag))).isEqualTo(BridgeErrorCategory.AUTHENTICATION);
    }

    @Test
    void permissionDeniedError() {
        var diag = new TfDiagnostic(TfDiagnostic.Severity.ERROR, "Error", "PERMISSION_DENIED: no access");
        assertThat(TerraformErrorClassifier.classify(List.of(diag))).isEqualTo(BridgeErrorCategory.AUTHENTICATION);
    }

    @Test
    void accessDeniedError() {
        var diag = new TfDiagnostic(TfDiagnostic.Severity.ERROR, "AccessDenied", "Forbidden");
        assertThat(TerraformErrorClassifier.classify(List.of(diag))).isEqualTo(BridgeErrorCategory.AUTHENTICATION);
    }

    @Test
    void validationError() {
        var diag = new TfDiagnostic(TfDiagnostic.Severity.ERROR, "Invalid configuration", "Missing required field");
        assertThat(TerraformErrorClassifier.classify(List.of(diag))).isEqualTo(BridgeErrorCategory.PERMANENT);
    }

    @Test
    void unknownErrorDefaultsToTransient() {
        var diag = new TfDiagnostic(TfDiagnostic.Severity.ERROR, "Something went wrong", "Unknown");
        assertThat(TerraformErrorClassifier.classify(List.of(diag))).isEqualTo(BridgeErrorCategory.TRANSIENT);
    }

    @Test
    void warningsIgnored() {
        var warning = new TfDiagnostic(TfDiagnostic.Severity.WARNING, "AuthFailure", "Should be ignored");
        assertThat(TerraformErrorClassifier.classify(List.of(warning))).isEqualTo(BridgeErrorCategory.TRANSIENT);
    }

    @Test
    void emptyDiagnosticsDefaultsToTransient() {
        assertThat(TerraformErrorClassifier.classify(List.of())).isEqualTo(BridgeErrorCategory.TRANSIENT);
    }

    @Test
    void authTakesPriorityOverValidation() {
        var auth = new TfDiagnostic(TfDiagnostic.Severity.ERROR, "Unauthorized", "No creds");
        var validation = new TfDiagnostic(TfDiagnostic.Severity.ERROR, "Invalid config", "Bad field");
        assertThat(TerraformErrorClassifier.classify(List.of(auth, validation)))
            .isEqualTo(BridgeErrorCategory.AUTHENTICATION);
    }

    @Test
    void nullSummaryAndDetailHandled() {
        var diag = new TfDiagnostic(TfDiagnostic.Severity.ERROR, null, null);
        assertThat(TerraformErrorClassifier.classify(List.of(diag))).isEqualTo(BridgeErrorCategory.TRANSIENT);
    }
}
