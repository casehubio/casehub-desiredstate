package io.casehub.desiredstate.example.bridge.cloud;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.TargetStatus;
import io.casehub.desiredstate.bridge.BridgeNodeSpec;

import java.util.ArrayList;
import java.util.List;

public class CloudGoalCompiler implements GoalCompiler<CloudBlueprint> {

    @Override
    public CompilationResult compile(CloudBlueprint goals, DesiredStateGraphFactory factory) {
        List<DesiredNode> nodes        = new ArrayList<>();
        List<Dependency>  dependencies = new ArrayList<>();

        for (var network : goals.networks()) {
            nodes.add(new DesiredNode(
                    NodeId.of(network.id()),
                    new BridgeNodeSpec(CloudNodeTypes.NETWORK, network.spec()),
                    HumanGating.NONE, null,
                    targetStatus(network.id(), goals)));
        }

        for (var vm : goals.vms()) {
            NodeId vmId = NodeId.of(vm.id());
            nodes.add(new DesiredNode(
                    vmId,
                    new BridgeNodeSpec(CloudNodeTypes.VM, vm.spec()),
                    HumanGating.NONE, null,
                    targetStatus(vm.id(), goals)));
            dependencies.add(new Dependency(vmId, NodeId.of(vm.networkId())));
        }

        for (var app : goals.apps()) {
            NodeId appId = NodeId.of(app.id());
            nodes.add(new DesiredNode(
                    appId,
                    new AppDeploymentSpec(app.appName(), app.version(), app.vmId()),
                    HumanGating.NONE, null,
                    targetStatus(app.id(), goals)));
            dependencies.add(new Dependency(appId, NodeId.of(app.vmId())));
        }

        for (var storage : goals.storages()) {
            nodes.add(new DesiredNode(
                    NodeId.of(storage.id()),
                    new BridgeNodeSpec(CloudNodeTypes.STORAGE, storage.spec()),
                    HumanGating.NONE, null,
                    targetStatus(storage.id(), goals)));
        }

        return CompilationResult.single(factory.of(nodes, dependencies));
    }

    private static TargetStatus targetStatus(String nodeId, CloudBlueprint goals) {
        return goals.suspendedNodeIds().contains(nodeId)
               ? TargetStatus.SUSPENDED
               : TargetStatus.ACTIVE;
    }
}
