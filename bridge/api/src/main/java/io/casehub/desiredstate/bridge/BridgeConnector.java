package io.casehub.desiredstate.bridge;

public interface BridgeConnector extends AutoCloseable {
    boolean isHealthy();
    <T> T send(BridgeOperation operation, Class<T> responseType);
}
