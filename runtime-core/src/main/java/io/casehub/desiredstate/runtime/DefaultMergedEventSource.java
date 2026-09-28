package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.EventSource;
import io.casehub.desiredstate.api.MergedEventSource;
import io.casehub.desiredstate.api.StateEvent;
import io.smallrye.mutiny.Multi;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

public class DefaultMergedEventSource implements MergedEventSource {

    private static final Logger LOG = Logger.getLogger(DefaultMergedEventSource.class.getName());

    private final List<EventSource> sources;


    protected DefaultMergedEventSource() {
        this.sources = List.of();
    }

    public DefaultMergedEventSource(Collection<EventSource> sources) {
        this.sources = List.copyOf(sources);
    }

    @Override
    public Multi<StateEvent> stream() {
        if (sources.isEmpty()) return Multi.createFrom().empty();
        if (sources.size() == 1) return sources.getFirst().stream();
        return Multi.createBy().merging().streams(
            sources.stream()
                .map(s -> s.stream()
                    .onFailure().retry()
                        .withBackOff(Duration.ofSeconds(1))
                        .atMost(3)
                    .onFailure().recoverWithMulti(failure -> {
                        LOG.warning("EventSource stream failed after retries: "
                            + failure.getMessage());
                        return Multi.createFrom().empty();
                    }))
                .toList()
        );
    }
}
