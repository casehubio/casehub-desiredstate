package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.casehub.desiredstate.testing.TestTimeouts.AWAIT;
import static io.casehub.desiredstate.testing.TestTimeouts.MUTINY_AWAIT;
import static org.assertj.core.api.Assertions.*;

class DefaultMergedEventSourceTest {

    @Test void emptySourcesProducesEmptyStream() {
        var merged = new DefaultMergedEventSource(List.of());
        List<StateEvent> events = merged.stream()
            .collect().asList().await().atMost(MUTINY_AWAIT);
        assertThat(events).isEmpty();
    }

    @Test void singleSourcePassesThrough() {
        var event = new StateEvent(NodeId.of("n1"), NodeStatus.PRESENT, "ok");
        EventSource source = () -> Multi.createFrom().item(event);

        var merged = new DefaultMergedEventSource(List.of(source));
        List<StateEvent> events = merged.stream()
            .collect().asList().await().atMost(MUTINY_AWAIT);
        assertThat(events).containsExactly(event);
    }

    @Test void multipleSourcesMergeEvents() {
        var e1 = new StateEvent(NodeId.of("n1"), NodeStatus.PRESENT, "from-a");
        var e2 = new StateEvent(NodeId.of("n2"), NodeStatus.ABSENT, "from-b");
        EventSource sourceA = () -> Multi.createFrom().item(e1);
        EventSource sourceB = () -> Multi.createFrom().item(e2);

        var merged = new DefaultMergedEventSource(List.of(sourceA, sourceB));
        List<StateEvent> events = merged.stream()
            .collect().asList().await().atMost(MUTINY_AWAIT);
        assertThat(events).containsExactlyInAnyOrder(e1, e2);
    }

    @Test void failedStreamDoesNotKillOtherStreams() {
        var e1 = new StateEvent(NodeId.of("n1"), NodeStatus.PRESENT, "ok");
        EventSource healthy = () -> Multi.createFrom().item(e1);
        EventSource failing = () -> Multi.createFrom().failure(new RuntimeException("boom"));

        var merged = new DefaultMergedEventSource(List.of(healthy, failing));
        List<StateEvent> events = merged.stream()
            .collect().asList().await().atMost(AWAIT);
        assertThat(events).contains(e1);
    }
}
