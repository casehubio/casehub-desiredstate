package io.casehub.desiredstate.example.bridge.cloud;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.bridge.*;
import io.casehub.desiredstate.runtime.*;
import io.casehub.desiredstate.bridge.runtime.BridgeDiscovery;
import io.casehub.desiredstate.bridge.runtime.BridgeManifestParser;
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
}
