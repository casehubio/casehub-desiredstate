package io.casehub.desiredstate.bridge.ansible;

public record AnsibleProcessResult(int exitCode, String stdout, String stderr) {
    public boolean succeeded() {
        return exitCode == 0;
    }
}
