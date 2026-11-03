package io.casehub.desiredstate.bridge.terraform;

public record TerraformSuspendConfig(
    String attribute,
    String suspendValue,
    String resumeValue
) {
    public TerraformSuspendConfig {
        if (attribute == null || attribute.isBlank()) {
            throw new IllegalArgumentException("suspend_attribute is required");
        }
        if (suspendValue == null || suspendValue.isBlank()) {
            throw new IllegalArgumentException("suspend_value is required");
        }
        if (resumeValue == null || resumeValue.isBlank()) {
            throw new IllegalArgumentException("resume_value is required");
        }
    }
}
