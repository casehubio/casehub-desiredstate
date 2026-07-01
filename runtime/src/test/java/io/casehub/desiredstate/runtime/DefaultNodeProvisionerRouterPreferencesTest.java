package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.testing.MockNodeProvisioner;
import io.casehub.platform.api.preferences.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class DefaultNodeProvisionerRouterPreferencesTest {

    static final NodeType IOT_TYPE = NodeType.of("physical-device");

    @Test
    void preferencesOverrideProvisionerDefault() {
        var prov = mockProvisioner(Set.of(IOT_TYPE), Duration.ofSeconds(30));

        var prefs = new StubPreferences(Map.of(
            "desiredstate.resync.physical-device", "PT10S"
        ));
        var provider = new StubPreferenceProvider(prefs);

        var router = new DefaultNodeProvisionerRouter(List.of(prov), provider);

        assertThat(router.resyncIntervalFor(IOT_TYPE)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void preferencesOverrideBelowFloorThrows() {
        var prov = mockProvisioner(Set.of(IOT_TYPE), Duration.ofSeconds(30));

        var prefs = new StubPreferences(Map.of(
            "desiredstate.resync.physical-device", "PT0S"
        ));
        var provider = new StubPreferenceProvider(prefs);

        var router = new DefaultNodeProvisionerRouter(List.of(prov), provider);

        assertThatThrownBy(() -> router.resyncIntervalFor(IOT_TYPE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be");
    }

    @Test
    void noPreferencesOverrideFallsBackToProvisionerDefault() {
        var prov = mockProvisioner(Set.of(IOT_TYPE), Duration.ofSeconds(30));
        var provider = new StubPreferenceProvider(new StubPreferences(Map.of()));

        var router = new DefaultNodeProvisionerRouter(List.of(prov), provider);

        assertThat(router.resyncIntervalFor(IOT_TYPE)).isEqualTo(Duration.ofSeconds(30));
    }

    private MockNodeProvisioner mockProvisioner(Set<NodeType> types, Duration resync) {
        var mock = new MockNodeProvisioner();
        mock.setHandledTypes(types);
        mock.setResyncInterval(resync);
        return mock;
    }

    // Stub implementations for testing without CDI
    record StubPreferenceProvider(Preferences prefs) implements PreferenceProvider {
        @Override
        public Preferences resolve(SettingsScope scope) { return prefs; }
    }

    static class StubPreferences implements Preferences {
        private final Map<String, String> values;
        StubPreferences(Map<String, String> values) { this.values = values; }

        @Override
        public <T extends SingleValuePreference> T get(PreferenceKey<T> key) { return null; }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends MultiValuePreference> T get(PreferenceKey<T> key, String subKey) {
            String raw = values.get(key.qualifiedName() + "." + subKey);
            if (raw == null) return null;
            return (T) key.parse(raw);
        }

        @Override
        public Map<String, Object> asMap() { return Map.copyOf(values); }
    }
}
