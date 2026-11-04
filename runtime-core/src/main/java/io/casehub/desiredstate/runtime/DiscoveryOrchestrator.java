package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.DiscoveredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ReconciliationStateStore;

import java.util.List;
import java.util.Set;

public class DiscoveryOrchestrator {

    private final DefaultDiscoveryAdapterRouter router;
    private final DesiredStateGraphFactory graphFactory;
    private final ReconciliationStateStore stateStore;

    public DiscoveryOrchestrator(DefaultDiscoveryAdapterRouter router,
                                  DesiredStateGraphFactory graphFactory,
                                  ReconciliationStateStore stateStore) {
        this.router = router;
        this.graphFactory = graphFactory;
        this.stateStore = stateStore;
    }

    public DiscoveryResult discover(DiscoveryRequest request) {
        Set<NodeType> types = request.types().isEmpty()
            ? router.allHandledTypes()
            : request.types();
        List<DiscoveredNode> resources = router.discover(types, request.tenancyId());
        DesiredStateGraph graph = buildGraph(resources);
        return new DiscoveryResult(resources, graph);
    }

    public void adoptBaseline(String tenancyId, DesiredStateGraph graph) {
        stateStore.store(tenancyId, graph);
    }

    private DesiredStateGraph buildGraph(List<DiscoveredNode> resources) {
        List<DesiredNode> nodes = resources.stream()
            .map(this::toDesiredNode)
            .toList();
        return graphFactory.of(nodes, List.of());
    }

    private DesiredNode toDesiredNode(DiscoveredNode discovered) {
        return new DesiredNode(
            NodeId.of(sanitizeId(discovered.resourceId())),
            discovered.toNodeSpec(),
            HumanGating.NONE);
    }

    static String sanitizeId(String resourceId) {
        return resourceId.toLowerCase()
            .replaceAll("[^a-z0-9-]", "-")
            .replaceAll("-+", "-")
            .replaceFirst("^-", "")
            .replaceFirst("-$", "");
    }
}
