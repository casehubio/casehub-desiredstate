package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.MapNodeSpec;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.bridge.BridgeDescriptor;
import io.casehub.desiredstate.bridge.BridgeNodeSpec;
import io.casehub.desiredstate.bridge.BridgeOutputStateStore;
import io.casehub.desiredstate.bridge.BridgeProvider;
import io.casehub.desiredstate.bridge.BridgeReadRequest;
import io.casehub.desiredstate.bridge.BridgeStateResult;
import io.casehub.desiredstate.bridge.BridgeTypeDescriptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class BridgeActualStateAdapter implements ActualStateAdapter {

    private final BridgeDescriptor descriptor;
    private final BridgeProvider provider;
    private final BridgeOutputStateStore stateStore;

    public BridgeActualStateAdapter(BridgeDescriptor descriptor, BridgeProvider provider) {
        this(descriptor, provider, new NoOpBridgeOutputStateStore());
    }

    public BridgeActualStateAdapter(BridgeDescriptor descriptor, BridgeProvider provider,
                                    BridgeOutputStateStore stateStore) {
        this.descriptor = descriptor;
        this.provider = provider;
        this.stateStore = stateStore;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return descriptor.handledTypes();
    }

    @Override
    public ActualState readActual(DesiredStateGraph graph, String tenancyId) {
        Map<NodeId, NodeStatus> statuses = new LinkedHashMap<>();

        for (var node : graph.nodes().values()) {
            if (!descriptor.handledTypes().contains(node.type())) continue;

            var typeDesc = descriptor.typeDescriptors().get(node.type().value());
            if (typeDesc == null) continue;

            Map<String, Object> previousState = stateStore.load(tenancyId, node.id().value())
                .orElse(Map.of());
            Map<String, Object> desiredSpec = extractFields(node.spec());
            var request = new BridgeReadRequest(node.type().value(), node.id().value(),
                tenancyId, previousState, desiredSpec);
            var result = provider.readState(request);

            NodeStatus status = mapStateResult(result, typeDesc);
            statuses.put(node.id(), status);
        }

        return new ActualState(statuses);
    }

    private NodeStatus mapStateResult(BridgeStateResult result, BridgeTypeDescriptor typeDesc) {
        return switch (result) {
            case BridgeStateResult.NotFound n -> typeDesc.notFoundState();
            case BridgeStateResult.Error e -> NodeStatus.UNKNOWN;
            case BridgeStateResult.Found f -> {
                var status = f.state().get("status");
                if (status instanceof String s) {
                    var mapped = typeDesc.stateMapping().get(s);
                    yield mapped != null ? mapped : typeDesc.defaultState();
                }
                yield typeDesc.defaultState();
            }
        };
    }

    private Map<String, Object> extractFields(NodeSpec spec) {
        if (spec instanceof BridgeNodeSpec bns) {return bns.fields();}
        if (spec instanceof MapNodeSpec mns) {return mns.fields();}
        return Map.of();
    }

}
