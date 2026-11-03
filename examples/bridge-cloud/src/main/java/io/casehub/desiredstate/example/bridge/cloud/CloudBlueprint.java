package io.casehub.desiredstate.example.bridge.cloud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record CloudBlueprint(
    List<NetworkEntry> networks,
    List<VmEntry> vms,
    List<AppEntry> apps,
    List<StorageEntry> storages
) {
    public record NetworkEntry(String id, Map<String, Object> spec) {}
    public record VmEntry(String id, String networkId, Map<String, Object> spec) {}
    public record AppEntry(String id, String vmId, String appName, String version) {}
    public record StorageEntry(String id, Map<String, Object> spec) {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final List<NetworkEntry> networks = new ArrayList<>();
        private final List<VmEntry> vms = new ArrayList<>();
        private final List<AppEntry> apps = new ArrayList<>();
        private final List<StorageEntry> storages = new ArrayList<>();

        public Builder network(String id, Map<String, Object> spec) {
            networks.add(new NetworkEntry(id, spec));
            return this;
        }

        public Builder vm(String id, String networkId, Map<String, Object> spec) {
            vms.add(new VmEntry(id, networkId, spec));
            return this;
        }

        public Builder app(String id, String vmId, String appName, String version) {
            apps.add(new AppEntry(id, vmId, appName, version));
            return this;
        }

        public Builder storage(String id, Map<String, Object> spec) {
            storages.add(new StorageEntry(id, spec));
            return this;
        }

        public CloudBlueprint build() {
            return new CloudBlueprint(
                List.copyOf(networks), List.copyOf(vms),
                List.copyOf(apps), List.copyOf(storages));
        }
    }
}
