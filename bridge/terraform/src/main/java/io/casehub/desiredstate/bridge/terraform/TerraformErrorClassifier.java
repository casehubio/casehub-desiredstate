package io.casehub.desiredstate.bridge.terraform;

import io.casehub.desiredstate.bridge.BridgeErrorCategory;

import java.util.List;
import java.util.Locale;

public final class TerraformErrorClassifier {

    private TerraformErrorClassifier() {}

    public static BridgeErrorCategory classify(List<TfDiagnostic> diagnostics) {
        for (TfDiagnostic d : diagnostics) {
            if (d.severity() != TfDiagnostic.Severity.ERROR) continue;
            String text = ((d.summary() != null ? d.summary() : "") + " "
                + (d.detail() != null ? d.detail() : "")).toLowerCase(Locale.ROOT);
            if (text.contains("auth") || text.contains("credential")
                || text.contains("permission_denied") || text.contains("accessdenied")
                || text.contains("unauthorized")) {
                return BridgeErrorCategory.AUTHENTICATION;
            }
            if (text.contains("invalid") || text.contains("validation")
                || text.contains("missing required")) {
                return BridgeErrorCategory.PERMANENT;
            }
        }
        return BridgeErrorCategory.TRANSIENT;
    }
}
