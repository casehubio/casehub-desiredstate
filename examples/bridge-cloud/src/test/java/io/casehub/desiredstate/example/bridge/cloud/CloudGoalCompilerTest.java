package io.casehub.desiredstate.example.bridge.cloud;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.TargetStatus;
import io.casehub.desiredstate.bridge.BridgeNodeSpec;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CloudGoalCompilerTest {

    private final DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
    private final CloudGoalCompiler compiler = new CloudGoalCompiler();

    @Test
    void compilesNetworkVmAndAppNodes() {
        var blueprint = CloudBlueprint.builder()
            .network("network-1", Map.of("cidr", "10.0.0.0/16"))
            .vm("vm-web", "network-1", Map.of("instance_type", "t3.micro"))
            .app("app-web", "vm-web", "web-service", "1.0.0")
            .build();

        var result = compiler.compile(blueprint, factory);
        assertThat(result).isInstanceOf(CompilationResult.SingleGraph.class);
        var graph = ((CompilationResult.SingleGraph) result).graph();

        assertThat(graph.nodes()).hasSize(3);
    }

    @Test
    void networkIsRootVmDependsOnNetworkAppDependsOnVm() {
        var blueprint = CloudBlueprint.builder()
            .network("net-1", Map.of("cidr", "10.0.0.0/16"))
            .vm("vm-1", "net-1", Map.of("instance_type", "t3.micro"))
            .app("app-1", "vm-1", "my-app", "1.0.0")
            .build();

        var graph = ((CompilationResult.SingleGraph) compiler.compile(blueprint, factory)).graph();

        assertThat(graph.roots())
            .containsExactly(NodeId.of("net-1"));
        assertThat(graph.dependenciesOf(NodeId.of("vm-1")))
            .containsExactly(NodeId.of("net-1"));
        assertThat(graph.dependenciesOf(NodeId.of("app-1")))
            .containsExactly(NodeId.of("vm-1"));
    }

    @Test
    void bridgeNodesUseBridgeNodeSpecJavaNodesUseAppDeploymentSpec() {
        var blueprint = CloudBlueprint.builder()
            .network("net-1", Map.of("cidr", "10.0.0.0/16"))
            .vm("vm-1", "net-1", Map.of("instance_type", "t3.micro"))
            .app("app-1", "vm-1", "my-app", "1.0.0")
            .build();

        var graph = ((CompilationResult.SingleGraph) compiler.compile(blueprint, factory)).graph();

        var netNode = graph.nodes().get(NodeId.of("net-1"));
        assertThat(netNode).isNotNull();
        assertThat(netNode.spec()).isInstanceOf(BridgeNodeSpec.class);
        assertThat(netNode.type()).isEqualTo(CloudNodeTypes.NETWORK);

        var vmNode = graph.nodes().get(NodeId.of("vm-1"));
        assertThat(vmNode).isNotNull();
        assertThat(vmNode.spec()).isInstanceOf(BridgeNodeSpec.class);
        assertThat(vmNode.type()).isEqualTo(CloudNodeTypes.VM);

        var appNode = graph.nodes().get(NodeId.of("app-1"));
        assertThat(appNode).isNotNull();
        assertThat(appNode.spec()).isInstanceOf(AppDeploymentSpec.class);
        assertThat(appNode.type()).isEqualTo(CloudNodeTypes.APP_DEPLOYMENT);
    }

    @Test
    void storageIsIndependentNode() {
        var blueprint = CloudBlueprint.builder()
            .storage("bucket-1", Map.of("region", "us-east-1"))
            .build();

        var graph = ((CompilationResult.SingleGraph) compiler.compile(blueprint, factory)).graph();

        assertThat(graph.nodes()).hasSize(1);
        assertThat(graph.roots()).hasSize(1);
        var node = graph.nodes().values().iterator().next();
        assertThat(node.type()).isEqualTo(CloudNodeTypes.STORAGE);
    }

    @Test
    void suspendedNodeIdsSetTargetStatusToSuspended() {
        var blueprint = CloudBlueprint.builder()
                                      .network("net-1", Map.of("cidr", "10.0.0.0/16"))
                                      .vm("vm-1", "net-1", Map.of("instance_type", "t3.micro"))
                                      .app("app-1", "vm-1", "my-app", "1.0.0")
                                      .storage("bucket-1", Map.of("region", "us-east-1"))
                                      .suspended("vm-1", "app-1")
                                      .build();

        var graph = ((CompilationResult.SingleGraph) compiler.compile(blueprint, factory)).graph();

        assertThat(graph.nodes().get(NodeId.of("vm-1")).targetStatus()).isEqualTo(TargetStatus.SUSPENDED);
        assertThat(graph.nodes().get(NodeId.of("app-1")).targetStatus()).isEqualTo(TargetStatus.SUSPENDED);
        assertThat(graph.nodes().get(NodeId.of("net-1")).targetStatus()).isEqualTo(TargetStatus.ACTIVE);
        assertThat(graph.nodes().get(NodeId.of("bucket-1")).targetStatus()).isEqualTo(TargetStatus.ACTIVE);
    }

    @Test
    void defaultBlueprintHasAllNodesActive() {
        var blueprint = CloudBlueprint.builder()
                                      .network("net-1", Map.of("cidr", "10.0.0.0/16"))
                                      .vm("vm-1", "net-1", Map.of("instance_type", "t3.micro"))
                                      .build();

        var graph = ((CompilationResult.SingleGraph) compiler.compile(blueprint, factory)).graph();

        assertThat(graph.nodes().values())
                .allMatch(n -> n.targetStatus() == TargetStatus.ACTIVE);
    }

}
