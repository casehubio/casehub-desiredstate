package io.casehub.desiredstate.bridge.terraform;

public class ProviderPoolExhaustedException extends RuntimeException {
    public ProviderPoolExhaustedException(int maxProcesses) {
        super("Provider process pool exhausted (max=" + maxProcesses
              + "). All processes have active tenants.");
    }
}
