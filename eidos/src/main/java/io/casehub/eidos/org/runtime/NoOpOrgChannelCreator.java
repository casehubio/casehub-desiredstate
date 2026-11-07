package io.casehub.eidos.org.runtime;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@DefaultBean
@ApplicationScoped
public class NoOpOrgChannelCreator implements OrgChannelCreator {
    private static final Logger LOG = Logger.getLogger(NoOpOrgChannelCreator.class);

    @Override
    public boolean createGroupChannel(ChannelRequest request) {
        LOG.debugv("No channel creator configured — skipping channel for group {0}", request.groupId());
        return false;
    }
}
