package io.casehub.desiredstate.bridge.ansible;

public record AnsiblePlaybookResult(AnsibleTaskResult taskResult, boolean succeeded) {}
