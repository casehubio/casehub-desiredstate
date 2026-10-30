package io.casehub.desiredstate.bridge.testing;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.bridge.*;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.*;
import java.util.stream.Collectors;

public class BridgeTestExtension implements BeforeEachCallback {

    private final String protocolId;
    private final String[] resourceTypes;
    private final Map<String, NodeStatus> stateMapping;
    private final NodeStatus defaultState;
    private final NodeStatus notFoundState;

    private MockBridgeProvider mockProvider;
    private BridgeDescriptor descriptor;

    private BridgeTestExtension(String protocolId, String[] resourceTypes,
                                 Map<String, NodeStatus> stateMapping,
                                 NodeStatus defaultState, NodeStatus notFoundState) {
        this.protocolId = protocolId;
        this.resourceTypes = resourceTypes;
        this.stateMapping = stateMapping;
        this.defaultState = defaultState;
        this.notFoundState = notFoundState;
    }

    public static BridgeTestExtension forTypes(String protocolId, String... resourceTypes) {
        return new BridgeTestExtension(protocolId, resourceTypes,
            Map.of("active", NodeStatus.PRESENT, "stopped", NodeStatus.SUSPENDED),
            NodeStatus.DRIFTED, NodeStatus.ABSENT);
    }

    public static Builder builder(String protocolId, String... resourceTypes) {
        return new Builder(protocolId, resourceTypes);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        mockProvider = new MockBridgeProvider(protocolId, resourceTypes);

        Set<NodeType> handledTypes = Arrays.stream(resourceTypes)
            .map(NodeType::of)
            .collect(Collectors.toSet());

        Map<String, BridgeTypeDescriptor> typeDescriptors = new LinkedHashMap<>();
        for (String type : resourceTypes) {
            typeDescriptors.put(type, new BridgeTypeDescriptor(
                NodeType.of(type), stateMapping, defaultState, notFoundState,
                Map.of(), BridgeErrorCategory.PERMANENT));
        }

        descriptor = new BridgeDescriptor(protocolId, handledTypes, typeDescriptors,
            new BridgeLifecycleConfig(LifecycleMode.MANAGED_PROCESS, null, null, null, null, null));
    }

    public MockBridgeProvider mockProvider() { return mockProvider; }
    public BridgeDescriptor descriptor() { return descriptor; }

    public static class Builder {
        private final String protocolId;
        private final String[] resourceTypes;
        private final Map<String, NodeStatus> stateMapping = new LinkedHashMap<>();
        private NodeStatus defaultState = NodeStatus.DRIFTED;
        private NodeStatus notFoundState = NodeStatus.ABSENT;

        Builder(String protocolId, String[] resourceTypes) {
            this.protocolId = protocolId;
            this.resourceTypes = resourceTypes;
        }

        public Builder stateMapping(String providerState, NodeStatus nodeStatus) {
            stateMapping.put(providerState, nodeStatus);
            return this;
        }

        public Builder defaultState(NodeStatus status) {
            this.defaultState = status;
            return this;
        }

        public Builder notFoundState(NodeStatus status) {
            this.notFoundState = status;
            return this;
        }

        public BridgeTestExtension build() {
            Map<String, NodeStatus> mapping = stateMapping.isEmpty()
                ? Map.of("active", NodeStatus.PRESENT, "stopped", NodeStatus.SUSPENDED)
                : Map.copyOf(stateMapping);
            return new BridgeTestExtension(protocolId, resourceTypes, mapping,
                defaultState, notFoundState);
        }
    }
}
