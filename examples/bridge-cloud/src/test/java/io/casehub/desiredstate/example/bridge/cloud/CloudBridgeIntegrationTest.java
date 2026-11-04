package io.casehub.desiredstate.example.bridge.cloud;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.LifecycleStepExecutor;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TargetStatus;
import io.casehub.desiredstate.bridge.BridgeImportResult;
import io.casehub.desiredstate.bridge.BridgeNodeSpec;
import io.casehub.desiredstate.bridge.BridgeResult;
import io.casehub.desiredstate.bridge.BridgeReadRequest;
import io.casehub.desiredstate.bridge.BridgeRequest;
import io.casehub.desiredstate.bridge.BridgeStateResult;
import io.casehub.desiredstate.bridge.ChangeAction;
import io.casehub.desiredstate.bridge.runtime.BridgeDiscovery;
import io.casehub.desiredstate.bridge.runtime.BridgeManifestParser;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.DefaultNodeProvisionerRouter;
import io.casehub.desiredstate.runtime.NoOpHumanNodeHandler;
import io.casehub.desiredstate.runtime.NoOpPendingApprovalHandler;
import io.casehub.desiredstate.runtime.NodeStepExecutor;
import io.casehub.desiredstate.runtime.SimpleTransitionExecutor;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CloudBridgeIntegrationTest {

    private static final LifecycleStepExecutor noOpLifecycle = (step, tenancyId) -> new StepOutcome.Succeeded();

    private MockCloudProvider cloudProvider;
    private AppDeploymentProvisioner appProvisioner;
    private CloudWorld world;
    private TransitionPlanner planner;
    private DesiredStateGraphFactory graphFactory;

    @BeforeEach
    void setUp() {
        cloudProvider = new MockCloudProvider();
        world = new CloudWorld();
        appProvisioner = new AppDeploymentProvisioner(world);
        graphFactory = new DefaultDesiredStateGraphFactory();
        planner = new TransitionPlanner();
    }

    @Test
    void mixedGraphProvisionsBridgeAndJavaNodes() {
        var blueprint = CloudBlueprint.builder()
            .network("net-1", Map.of("cidr", "10.0.0.0/16"))
            .vm("vm-1", "net-1", Map.of("instance_type", "t3.micro"))
            .app("app-1", "vm-1", "my-service", "1.0.0")
            .storage("bucket-1", Map.of("region", "us-east-1"))
            .build();

        var compiler = new CloudGoalCompiler();
        var graph = ((CompilationResult.SingleGraph) compiler.compile(blueprint, graphFactory)).graph();

        var descriptor = BridgeManifestParser.parse(
            getClass().getResourceAsStream("/META-INF/desiredstate/bridges/mock-cloud.yaml"));
        var bridgeBeans = BridgeDiscovery.discoverAll(List.of(descriptor), List.of(cloudProvider));

        var bridgeProvisioner = (NodeProvisioner) bridgeBeans.stream()
            .filter(b -> b.type() == NodeProvisioner.class)
            .findFirst().orElseThrow().instance();

        var router = new DefaultNodeProvisionerRouter(List.of(bridgeProvisioner, appProvisioner));

        var actual = new ActualState(Map.of());
        var plan = planner.plan(graph, actual);

        var executor = new SimpleTransitionExecutor(
            new NodeStepExecutor(router, new NoOpHumanNodeHandler(), new NoOpPendingApprovalHandler(), noOpLifecycle));
        var result = executor.execute(plan, "tenant-1");

        assertThat(result.outcomes().values())
            .allMatch(o -> o instanceof StepOutcome.Succeeded || o instanceof StepOutcome.AlreadyConverged);
        assertThat(world.deployments()).containsKey("app-1");
        assertThat(world.deployments().get("app-1").appName()).isEqualTo("my-service");
    }

    @Test
    void importSeedsStateFromExistingCloudResource() {
        cloudProvider.seedResource("cloud:vm", "i-existing",
            Map.of("status", "running", "instance_type", "t3.large"));

        var result = cloudProvider.importResource("cloud:vm", "i-existing", "tenant-1");

        assertThat(result).isInstanceOf(BridgeImportResult.Success.class);
        var success = (BridgeImportResult.Success) result;
        assertThat(success.state()).containsEntry("instance_type", "t3.large");
    }

    @Test
    void planDetectsCreateUpdateAndNoChange() {
        var createReq = new BridgeRequest(
            "cloud:vm", "vm-new", Map.of("instance_type", "t3.micro"), "tenant-1");
        var createPlan = cloudProvider.plan(createReq);
        assertThat(createPlan.resourceChanges()).hasSize(1);
        assertThat(createPlan.resourceChanges().getFirst().action())
            .isEqualTo(ChangeAction.CREATE);

        cloudProvider.apply(createReq);

        var noChangePlan = cloudProvider.plan(createReq);
        assertThat(noChangePlan.resourceChanges()).isEmpty();

        var updateReq = new BridgeRequest(
            "cloud:vm", "vm-new", Map.of("instance_type", "t3.large"), "tenant-1");
        var updatePlan = cloudProvider.plan(updateReq);
        assertThat(updatePlan.resourceChanges()).hasSize(1);
        assertThat(updatePlan.resourceChanges().getFirst().action())
            .isEqualTo(ChangeAction.UPDATE);
    }

    @Test
    void suspendWorksAcrossBridgeAndJavaProvisioners() {
        var blueprint = CloudBlueprint.builder()
                                      .network("net-1", Map.of("cidr", "10.0.0.0/16"))
                                      .vm("vm-1", "net-1", Map.of("instance_type", "t3.micro"))
                                      .app("app-1", "vm-1", "my-service", "1.0.0")
                                      .storage("bucket-1", Map.of("region", "us-east-1"))
                                      .build();

        var compiler    = new CloudGoalCompiler();
        var activeGraph = ((CompilationResult.SingleGraph) compiler.compile(blueprint, graphFactory)).graph();

        var descriptor = BridgeManifestParser.parse(
                getClass().getResourceAsStream("/META-INF/desiredstate/bridges/mock-cloud.yaml"));
        var bridgeBeans = BridgeDiscovery.discoverAll(List.of(descriptor), List.of(cloudProvider));

        var bridgeProvisioner = (NodeProvisioner) bridgeBeans.stream()
                                                             .filter(b -> b.type() == NodeProvisioner.class)
                                                             .findFirst().orElseThrow().instance();

        var router = new DefaultNodeProvisionerRouter(List.of(bridgeProvisioner, appProvisioner));
        var executor = new SimpleTransitionExecutor(
                new NodeStepExecutor(router, new NoOpHumanNodeHandler(), new NoOpPendingApprovalHandler(), noOpLifecycle));

        // Phase 1: provision everything
        var provisionPlan   = planner.plan(activeGraph, new ActualState(Map.of()));
        var provisionResult = executor.execute(provisionPlan, "tenant-1");
        assertThat(provisionResult.outcomes().values())
                .allMatch(o -> o instanceof StepOutcome.Succeeded);

        // Phase 2: build a new desired graph with VM and app suspended
        var suspendedGraph = graphFactory.of(
                List.of(
                        new DesiredNode(NodeId.of("net-1"),
                                        new BridgeNodeSpec(CloudNodeTypes.NETWORK, Map.of("cidr", "10.0.0.0/16")),
                                        HumanGating.NONE),
                        new DesiredNode(NodeId.of("vm-1"),
                                        new BridgeNodeSpec(CloudNodeTypes.VM, Map.of("instance_type", "t3.micro")),
                                        HumanGating.NONE, null, TargetStatus.SUSPENDED),
                        new DesiredNode(NodeId.of("app-1"),
                                        new AppDeploymentSpec("my-service", "1.0.0", "vm-1"),
                                        HumanGating.NONE, null, TargetStatus.SUSPENDED),
                        new DesiredNode(NodeId.of("bucket-1"),
                                        new BridgeNodeSpec(CloudNodeTypes.STORAGE, Map.of("region", "us-east-1")),
                                        HumanGating.NONE)
                       ),
                List.of(
                        new Dependency(NodeId.of("vm-1"), NodeId.of("net-1")),
                        new Dependency(NodeId.of("app-1"), NodeId.of("vm-1"))
                       )
                                            );

        // Actual state: everything is PRESENT after provisioning
        var presentState = new ActualState(Map.of(
                NodeId.of("net-1"), NodeStatus.PRESENT,
                NodeId.of("vm-1"), NodeStatus.PRESENT,
                NodeId.of("app-1"), NodeStatus.PRESENT,
                NodeId.of("bucket-1"), NodeStatus.PRESENT
                                                 ));

        var suspendPlan = planner.plan(suspendedGraph, presentState, null, router::supportsStatefulLifecycle);
        assertThat(suspendPlan.flatSuspensions()).isNotEmpty();
        assertThat(suspendPlan.flatAdditions()).isEmpty();
        assertThat(suspendPlan.flatRemovals()).isEmpty();

        var suspendResult = executor.execute(suspendPlan, "tenant-1");
        assertThat(suspendResult.outcomes().values())
                .allMatch(o -> o instanceof StepOutcome.Succeeded || o instanceof StepOutcome.AlreadyConverged);

        // Verify bridge provisioner suspended the VM (status changed to "stopped")
        var vmState = cloudProvider.readState(new BridgeReadRequest("cloud:vm", "vm-1", "tenant-1"));
        assertThat(vmState).isInstanceOf(BridgeStateResult.Found.class);
        assertThat(((BridgeStateResult.Found) vmState).state()).containsEntry("status", "stopped");

        // Verify Java provisioner suspended the app deployment
        assertThat(world.isSuspended("app-1")).isTrue();
        assertThat(world.deployments()).containsKey("app-1");

        // Phase 3: resume — switch back to ACTIVE
        var resumePlan = planner.plan(activeGraph, new ActualState(Map.of(
            NodeId.of("net-1"), NodeStatus.PRESENT,
            NodeId.of("vm-1"), NodeStatus.SUSPENDED,
            NodeId.of("app-1"), NodeStatus.SUSPENDED,
            NodeId.of("bucket-1"), NodeStatus.PRESENT
        )), null, router::supportsStatefulLifecycle);
        assertThat(resumePlan.flatResumptions()).isNotEmpty();

        var resumeResult = executor.execute(resumePlan, "tenant-1");
        assertThat(resumeResult.outcomes().values())
            .allMatch(o -> o instanceof StepOutcome.Succeeded || o instanceof StepOutcome.AlreadyConverged);

        // Verify bridge provisioner resumed the VM
        var resumedVmState = cloudProvider.readState(new BridgeReadRequest("cloud:vm", "vm-1", "tenant-1"));
        assertThat(((BridgeStateResult.Found) resumedVmState).state()).containsEntry("status", "running");

        // Verify Java provisioner resumed the app deployment
        assertThat(world.isSuspended("app-1")).isFalse();
    }

    @Test
    void suspendFailsForNonSuspendableTypes() {
        cloudProvider.seedResource("cloud:storage", "bucket-1",
                                   Map.of("status", "available", "region", "us-east-1"));

        var result = cloudProvider.suspend(new BridgeRequest(
                "cloud:storage", "bucket-1", Map.of("region", "us-east-1"), "tenant-1"));

        assertThat(result).isInstanceOf(BridgeResult.Failed.class);
        assertThat(((BridgeResult.Failed) result).reason()).contains("Suspend not supported");
    }


}
