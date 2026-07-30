package io.casehub.desiredstate.persistence.jpa;

import io.casehub.desiredstate.api.NodeId;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class JpaFaultCountStoreTest {

    @Inject
    JpaFaultCountStore store;

    @BeforeEach
    void cleanTable() {
        store.evict("ns", "t1", Set.of());
        store.evict("ns", "t2", Set.of());
        store.evict("ns-a", "t1", Set.of());
        store.evict("ns-b", "t1", Set.of());
        store.evict("ns1", "t1", Set.of());
        store.evict("ns1", "t2", Set.of());
        store.evict("ns2", "t1", Set.of());
        store.evict("policy-a", "t1", Set.of());
        store.evict("policy-b", "t1", Set.of());
    }

    @Test
    void incrementAndGet_returnsSequentialCounts() {
        assertThat(store.incrementAndGet("ns", "t1", NodeId.of("n1"))).isEqualTo(1);
        assertThat(store.incrementAndGet("ns", "t1", NodeId.of("n1"))).isEqualTo(2);
        assertThat(store.incrementAndGet("ns", "t1", NodeId.of("n1"))).isEqualTo(3);
    }

    @Test
    void getCount_returnsZeroForAbsent() {
        assertThat(store.getCount("ns", "t1", NodeId.of("n1"))).isEqualTo(0);
    }

    @Test
    void reset_setsCountToZero() {
        store.incrementAndGet("ns", "t1", NodeId.of("n1"));
        store.incrementAndGet("ns", "t1", NodeId.of("n1"));
        store.reset("ns", "t1", NodeId.of("n1"));
        assertThat(store.getCount("ns", "t1", NodeId.of("n1"))).isEqualTo(0);
        assertThat(store.incrementAndGet("ns", "t1", NodeId.of("n1"))).isEqualTo(1);
    }

    @Test
    void reset_onNonExistentKeyCreatesZeroCountRow() {
        store.reset("ns", "t1", NodeId.of("never-seen"));
        assertThat(store.getCount("ns", "t1", NodeId.of("never-seen"))).isEqualTo(0);
        assertThat(store.incrementAndGet("ns", "t1", NodeId.of("never-seen"))).isEqualTo(1);
    }

    @Test
    void remove_deletesEntry() {
        store.incrementAndGet("ns", "t1", NodeId.of("n1"));
        store.incrementAndGet("ns", "t1", NodeId.of("n1"));
        store.remove("ns", "t1", NodeId.of("n1"));
        assertThat(store.getCount("ns", "t1", NodeId.of("n1"))).isEqualTo(0);
        assertThat(store.incrementAndGet("ns", "t1", NodeId.of("n1"))).isEqualTo(1);
    }

    @Test
    void evict_removesEntriesNotInRetainedSet() {
        store.incrementAndGet("ns", "t1", NodeId.of("a"));
        store.incrementAndGet("ns", "t1", NodeId.of("b"));
        store.incrementAndGet("ns", "t1", NodeId.of("c"));

        store.evict("ns", "t1", Set.of(NodeId.of("a"), NodeId.of("c")));

        assertThat(store.getCount("ns", "t1", NodeId.of("a"))).isEqualTo(1);
        assertThat(store.getCount("ns", "t1", NodeId.of("b"))).isEqualTo(0);
        assertThat(store.getCount("ns", "t1", NodeId.of("c"))).isEqualTo(1);
    }

    @Test
    void evict_withEmptyRetainedSet_deletesAll() {
        store.incrementAndGet("ns", "t1", NodeId.of("a"));
        store.incrementAndGet("ns", "t1", NodeId.of("b"));

        store.evict("ns", "t1", Set.of());

        assertThat(store.getCount("ns", "t1", NodeId.of("a"))).isEqualTo(0);
        assertThat(store.getCount("ns", "t1", NodeId.of("b"))).isEqualTo(0);
    }

    @Test
    void tenantIsolation() {
        store.incrementAndGet("ns", "t1", NodeId.of("n1"));
        store.incrementAndGet("ns", "t1", NodeId.of("n1"));
        store.incrementAndGet("ns", "t2", NodeId.of("n1"));

        assertThat(store.getCount("ns", "t1", NodeId.of("n1"))).isEqualTo(2);
        assertThat(store.getCount("ns", "t2", NodeId.of("n1"))).isEqualTo(1);
    }

    @Test
    void namespaceIsolation() {
        store.incrementAndGet("policy-a", "t1", NodeId.of("n1"));
        store.incrementAndGet("policy-a", "t1", NodeId.of("n1"));
        store.incrementAndGet("policy-b", "t1", NodeId.of("n1"));

        assertThat(store.getCount("policy-a", "t1", NodeId.of("n1"))).isEqualTo(2);
        assertThat(store.getCount("policy-b", "t1", NodeId.of("n1"))).isEqualTo(1);
    }

    @Test
    void evict_scopedToNamespaceAndTenancy() {
        store.incrementAndGet("ns-a", "t1", NodeId.of("n1"));
        store.incrementAndGet("ns-b", "t1", NodeId.of("n1"));
        store.incrementAndGet("ns-a", "t2", NodeId.of("n1"));

        store.evict("ns-a", "t1", Set.of());

        assertThat(store.getCount("ns-a", "t1", NodeId.of("n1"))).isEqualTo(0);
        assertThat(store.getCount("ns-b", "t1", NodeId.of("n1"))).isEqualTo(1);
        assertThat(store.getCount("ns-a", "t2", NodeId.of("n1"))).isEqualTo(1);
    }

    @Test
    void evictAcrossNamespaces_removesNonRetainedAcrossAllNamespaces() {
        store.incrementAndGet("ns1", "t1", NodeId.of("a"));
        store.incrementAndGet("ns1", "t1", NodeId.of("b"));
        store.incrementAndGet("ns2", "t1", NodeId.of("a"));
        store.incrementAndGet("ns2", "t1", NodeId.of("c"));

        store.evictAcrossNamespaces("t1", Set.of(NodeId.of("a")));

        assertThat(store.getCount("ns1", "t1", NodeId.of("a"))).isEqualTo(1);
        assertThat(store.getCount("ns1", "t1", NodeId.of("b"))).isZero();
        assertThat(store.getCount("ns2", "t1", NodeId.of("a"))).isEqualTo(1);
        assertThat(store.getCount("ns2", "t1", NodeId.of("c"))).isZero();
    }

    @Test
    void evictAcrossNamespaces_emptyRetainedRemovesAllForTenant() {
        store.incrementAndGet("ns1", "t1", NodeId.of("a"));
        store.incrementAndGet("ns2", "t1", NodeId.of("b"));
        store.incrementAndGet("ns1", "t2", NodeId.of("c"));

        store.evictAcrossNamespaces("t1", Set.of());

        assertThat(store.getCount("ns1", "t1", NodeId.of("a"))).isZero();
        assertThat(store.getCount("ns2", "t1", NodeId.of("b"))).isZero();
        assertThat(store.getCount("ns1", "t2", NodeId.of("c"))).isEqualTo(1);
    }
}
