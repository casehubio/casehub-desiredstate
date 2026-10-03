package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.NotificationSink;

import java.util.logging.Logger;

public class LoggingNotificationSink implements NotificationSink {

    private static final Logger LOG = Logger.getLogger(LoggingNotificationSink.class.getName());

    @Override
    public void send(String channel, String message, String tenancyId) {
        LOG.info("[" + tenancyId + "] " + channel + ": " + message);
    }
}
