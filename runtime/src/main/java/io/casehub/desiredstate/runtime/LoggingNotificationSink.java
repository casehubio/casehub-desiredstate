package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.NotificationSink;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@DefaultBean
@ApplicationScoped
public class LoggingNotificationSink implements NotificationSink {

    private static final Logger LOG = Logger.getLogger(LoggingNotificationSink.class);

    @Override
    public void send(String channel, String message, String tenancyId) {
        LOG.infof("[%s] %s: %s", tenancyId, channel, message);
    }
}
