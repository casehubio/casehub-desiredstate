package io.casehub.desiredstate.api;

public interface NotificationSink {
    void send(String channel, String message, String tenancyId);
}
