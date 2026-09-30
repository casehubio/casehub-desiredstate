package io.casehub.desiredstate.plugin.testing;

import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.ProvisionResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class PluginTestAssertions {

    private PluginTestAssertions() {}

    public static void evaluate(Map<String, String> assertions, ActionResult result) {
        for (Map.Entry<String, String> entry : assertions.entrySet()) {
            switch (entry.getKey()) {
                case "provision" -> assertProvisionResult(entry.getValue(), result);
                case "deprovision" -> assertDeprovisionResult(entry.getValue(), result);
                case "actual-state" -> assertActualState(entry.getValue(), result);
                case "error-matches" -> assertErrorMatches(entry.getValue(), result);
                case "file-exists" -> assertFileExists(entry.getValue());
                case "file-absent" -> assertFileAbsent(entry.getValue());
                default -> throw new AssertionError(
                    "Unknown assertion key: '" + entry.getKey() + "'");
            }
        }
    }

    private static void assertProvisionResult(String expected, ActionResult result) {
        if (result.provisionResult() == null) {
            throw new AssertionError(failMsg("provision", expected, "no provision result"));
        }
        String actual = switch (result.provisionResult()) {
            case ProvisionResult.Success ignored -> "success";
            case ProvisionResult.AlreadyConverged ignored -> "already-converged";
            case ProvisionResult.Failed f -> "failed";
            case ProvisionResult.PendingApproval p -> "pending-approval";
        };
        if (!expected.equals(actual)) {
            String detail = actual;
            if (result.provisionResult() instanceof ProvisionResult.Failed f) {
                detail = "failed (" + f.reason() + ")";
            }
            throw new AssertionError(failMsg("provision", expected, detail));
        }
    }

    private static void assertDeprovisionResult(String expected, ActionResult result) {
        if (result.deprovisionResult() == null) {
            throw new AssertionError(failMsg("deprovision", expected, "no deprovision result"));
        }
        String actual = switch (result.deprovisionResult()) {
            case DeprovisionResult.Success ignored -> "success";
            case DeprovisionResult.Failed f -> "failed";
            case DeprovisionResult.PendingApproval p -> "pending-approval";
        };
        if (!expected.equals(actual)) {
            String detail = actual;
            if (result.deprovisionResult() instanceof DeprovisionResult.Failed f) {
                detail = "failed (" + f.reason() + ")";
            }
            throw new AssertionError(failMsg("deprovision", expected, detail));
        }
    }

    private static void assertActualState(String expected, ActionResult result) {
        if (result.actualState() == null) {
            throw new AssertionError(failMsg("actual-state", expected, "no actual-state result"));
        }
        String actual = result.actualState().name();
        if (!expected.equals(actual)) {
            throw new AssertionError(failMsg("actual-state", expected, actual));
        }
    }

    private static void assertErrorMatches(String pattern, ActionResult result) {
        String errorMsg = result.errorMessage();
        if (errorMsg == null) {
            throw new AssertionError(failMsg("error-matches", pattern, "no error message"));
        }
        if (!errorMsg.contains(pattern) && !errorMsg.matches(pattern)) {
            throw new AssertionError(failMsg("error-matches", pattern, errorMsg));
        }
    }

    private static void assertFileExists(String path) {
        if (!Files.exists(Path.of(path))) {
            throw new AssertionError(failMsg("file-exists", path, "file does not exist"));
        }
    }

    private static void assertFileAbsent(String path) {
        if (Files.exists(Path.of(path))) {
            throw new AssertionError(failMsg("file-absent", path, "file exists"));
        }
    }

    private static String failMsg(String key, String expected, String actual) {
        return "\n  Assertion:  " + key
            + "\n  Expected:   " + expected
            + "\n  Actual:     " + actual;
    }

    public record ActionResult(
            ProvisionResult provisionResult,
            DeprovisionResult deprovisionResult,
            NodeStatus actualState,
            String errorMessage
    ) {
        public static ActionResult ofProvision(ProvisionResult r) {
            String error = r instanceof ProvisionResult.Failed f ? f.reason() : null;
            return new ActionResult(r, null, null, error);
        }

        public static ActionResult ofDeprovision(DeprovisionResult r) {
            String error = r instanceof DeprovisionResult.Failed f ? f.reason() : null;
            return new ActionResult(null, r, null, error);
        }

        public static ActionResult ofActualState(NodeStatus status) {
            return new ActionResult(null, null, status, null);
        }

        public static ActionResult ofValidationError(String message) {
            return new ActionResult(null, null, null, message);
        }
    }
}
