package io.casehub.eidos.org.runtime;

import io.casehub.eidos.org.api.OrganizationalUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryOrgGoalStoreTest {

    private final InMemoryOrgGoalStore store = new InMemoryOrgGoalStore();

    @Test void storeAndLoad() {
        var unit = OrganizationalUnit.builder()
            .unitId("t1").name("T1").tenancyId("tenant-1").build();
        var goal = OrgGoal.empty().withUnitGoal(new OrgUnitGoal(unit));

        store.store("tenant-1", goal);
        var loaded = store.load("tenant-1");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().unitGoals()).hasSize(1);
    }

    @Test void loadMissingReturnsEmpty() {
        assertThat(store.load("missing")).isEmpty();
    }

    @Test void removeDeletesGoal() {
        store.store("t1", OrgGoal.empty());
        store.remove("t1");
        assertThat(store.load("t1")).isEmpty();
    }
}
