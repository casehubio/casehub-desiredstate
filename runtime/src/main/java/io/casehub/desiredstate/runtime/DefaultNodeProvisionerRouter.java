package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.platform.api.preferences.DurationPreference;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;

import java.time.Duration;
import java.util.*;

public class DefaultNodeProvisionerRouter implements NodeProvisionerRouter {

    static final Duration MIN_RESYNC = Duration.ofSeconds(1);
    static final Duration DEFAULT_RESYNC = Duration.ofMinutes(5);

    private final Map<NodeType, NodeProvisioner> routing;
    private final PreferenceProvider preferenceProvider;

    // No-arg PreferenceProvider constructor for tests without Preferences
    public DefaultNodeProvisionerRouter(Collection<NodeProvisioner> provisioners) {
        this(provisioners, scope -> new NoOpPreferences());
    }

    // Full constructor with Preferences
    public DefaultNodeProvisionerRouter(Collection<NodeProvisioner> provisioners,
                                         PreferenceProvider preferenceProvider) {
        Map<NodeType, NodeProvisioner> table = new LinkedHashMap<>();
        for (NodeProvisioner p : provisioners) {
            Duration interval = p.resyncInterval();
            if (interval == null || interval.compareTo(MIN_RESYNC) < 0) {
                throw new IllegalArgumentException(
                    p.getClass().getName() + ".resyncInterval() returned " + interval
                    + "; must be ≥ " + MIN_RESYNC);
            }
            for (NodeType type : p.handledTypes()) {
                NodeProvisioner existing = table.put(type, p);
                if (existing != null) {
                    throw new IllegalArgumentException(
                        "NodeType " + type.value() + " claimed by both "
                        + existing.getClass().getName() + " and "
                        + p.getClass().getName());
                }
            }
        }
        this.routing = Map.copyOf(table);
        this.preferenceProvider = preferenceProvider;
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        NodeProvisioner p = routing.get(node.type());
        if (p == null) {
            return new ProvisionResult.Failed(
                "No provisioner for node type: " + node.type().value());
        }
        return p.provision(node, context);
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        NodeProvisioner p = routing.get(node.type());
        if (p == null) {
            return new DeprovisionResult.Failed(
                "No provisioner for node type: " + node.type().value());
        }
        return p.deprovision(node, context);
    }

    @Override
    public Duration resyncIntervalFor(NodeType type) {
        if (preferenceProvider != null) {
            Preferences prefs = preferenceProvider.resolve(SettingsScope.root());
            DurationPreference override = prefs.get(
                DesiredStatePreferenceKeys.RESYNC_INTERVAL, type.value());
            if (override != null) {
                Duration value = override.duration();
                if (value.compareTo(MIN_RESYNC) < 0) {
                    throw new IllegalArgumentException(
                        "Preferences override for " + type.value() + " is "
                        + value + "; must be ≥ " + MIN_RESYNC);
                }
                return value;
            }
        }
        NodeProvisioner p = routing.get(type);
        return p != null ? p.resyncInterval() : DEFAULT_RESYNC;
    }

    @Override
    public Set<NodeType> allHandledTypes() {
        return routing.keySet();
    }

    // No-op Preferences implementation for tests without Preferences
    private static class NoOpPreferences implements Preferences {
        @Override
        public <T extends io.casehub.platform.api.preferences.SingleValuePreference> T get(
                io.casehub.platform.api.preferences.PreferenceKey<T> key) {
            return null;
        }

        @Override
        public <T extends io.casehub.platform.api.preferences.MultiValuePreference> T get(
                io.casehub.platform.api.preferences.PreferenceKey<T> key, String subKey) {
            return null;
        }

        @Override
        public Map<String, Object> asMap() {
            return Map.of();
        }
    }
}
