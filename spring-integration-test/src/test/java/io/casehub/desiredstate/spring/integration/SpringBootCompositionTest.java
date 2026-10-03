package io.casehub.desiredstate.spring.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringBootCompositionTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @LocalServerPort
    private int port;

    @Test
    void contextLoads() {}

    @Test
    void platformFallbackBeansRegistered() {
        assertThat(context.getBean(io.casehub.platform.api.identity.GroupMembershipProvider.class))
                .isNotNull();
        assertThat(context.getBean(io.casehub.platform.api.preferences.PreferenceSchemaRegistry.class))
                .isNotNull();
    }

    @Test
    void runtimeCoreBeans() {
        assertThat(context.getBean(io.casehub.desiredstate.runtime.TransitionPlanner.class)).isNotNull();
        assertThat(context.getBean(io.casehub.desiredstate.runtime.FaultPolicyEngine.class)).isNotNull();
        assertThat(context.getBean(io.casehub.desiredstate.runtime.SituationRecompilerEngine.class)).isNotNull();
        assertThat(context.getBean(io.casehub.desiredstate.runtime.CbrProposalTracker.class)).isNotNull();
        assertThat(context.getBean(io.casehub.desiredstate.runtime.CbrFaultPolicy.class)).isNotNull();
        assertThat(context.getBean(io.casehub.desiredstate.runtime.CbrSituationRecompiler.class)).isNotNull();
        assertThat(context.getBean(io.casehub.desiredstate.runtime.NodeStepExecutor.class)).isNotNull();
        assertThat(context.getBean(io.casehub.desiredstate.runtime.DriftPolicyEngine.class)).isNotNull();
        assertThat(context.getBean(io.casehub.desiredstate.runtime.PlanApprovalGate.class)).isNotNull();
        assertThat(context.getBean(io.casehub.desiredstate.runtime.ReconciliationEventEmitter.class)).isNotNull();
        assertThat(context.getBean(io.casehub.desiredstate.runtime.ReconciliationLoop.class)).isNotNull();
        assertThat(context.getBean(io.casehub.desiredstate.runtime.LifecycleManager.class)).isNotNull();
    }

    @Test
    void transitionExecutorResolvable() {
        assertThat(context.getBean(io.casehub.desiredstate.api.TransitionExecutor.class)).isNotNull();
    }

    @Test
    void fallbackBeansAreCorrectType() {
        assertThat(context.getBean(io.casehub.desiredstate.api.HumanNodeHandler.class))
                .isInstanceOf(io.casehub.desiredstate.runtime.NoOpHumanNodeHandler.class);
        assertThat(context.getBean(io.casehub.desiredstate.api.PendingApprovalHandler.class))
                .isInstanceOf(io.casehub.desiredstate.runtime.NoOpPendingApprovalHandler.class);
        assertThat(context.getBean(io.casehub.desiredstate.api.ConfigurationRetriever.class))
                .isInstanceOf(io.casehub.desiredstate.runtime.NoOpConfigurationRetriever.class);
        assertThat(context.getBean(io.casehub.desiredstate.api.ConfigurationAdapter.class))
                .isInstanceOf(io.casehub.desiredstate.runtime.NoOpConfigurationAdapter.class);
        assertThat(context.getBean(io.casehub.desiredstate.api.NotificationSink.class))
                .isInstanceOf(io.casehub.desiredstate.runtime.LoggingNotificationSink.class);
        assertThat(context.getBean(io.casehub.desiredstate.api.LifecycleStepExecutor.class))
                .isInstanceOf(io.casehub.desiredstate.runtime.DefaultLifecycleStepExecutor.class);
        assertThat(context.getBean(io.casehub.desiredstate.api.PlanApprovalPolicy.class))
                .isInstanceOf(io.casehub.desiredstate.runtime.NoOpPlanApprovalPolicy.class);
        assertThat(context.getBean(io.casehub.desiredstate.api.PlanApprovalHandler.class))
                .isInstanceOf(io.casehub.desiredstate.runtime.NoOpPlanApprovalHandler.class);
    }

    @Test
    void routerAndEventSourceFallbacks() {
        assertThat(context.getBean(io.casehub.desiredstate.api.MergedEventSource.class))
                .isInstanceOf(io.casehub.desiredstate.runtime.DefaultMergedEventSource.class);
        assertThat(context.getBean(io.casehub.desiredstate.api.ActualStateAdapterRouter.class))
                .isInstanceOf(io.casehub.desiredstate.runtime.DefaultActualStateAdapterRouter.class);
        assertThat(context.getBean(io.casehub.desiredstate.api.NodeProvisionerRouter.class))
                .isInstanceOf(io.casehub.desiredstate.runtime.DefaultNodeProvisionerRouter.class);
    }

    @Test
    void storeBeans() {
        assertThat(context.getBean(io.casehub.desiredstate.api.FaultCountStore.class)).isNotNull();
        assertThat(context.getBean(io.casehub.desiredstate.api.ReconciliationStateStore.class)).isNotNull();
        assertThat(context.getBean(io.casehub.desiredstate.api.ExemptionStore.class)).isNotNull();
        assertThat(context.getBean(io.casehub.desiredstate.api.DesiredStateGraphFactory.class))
                .isInstanceOf(io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory.class);
    }

    @Test
    void evictionListeners() {
        assertThat(context.getBean(io.casehub.desiredstate.runtime.FaultCountEvictionListener.class)).isNotNull();
        assertThat(context.getBean(io.casehub.desiredstate.runtime.ExemptionEvictionListener.class)).isNotNull();
    }

    @Test
    void transitionActionHandler() {
        assertThat(context.getBean(io.casehub.desiredstate.runtime.TransitionActionHandler.class)).isNotNull();
    }

    @Test
    void healthCheckReturnsUp() throws Exception {
        var client = HttpClient.newHttpClient();
        var request =
                HttpRequest.newBuilder()
                           .uri(URI.create("http://localhost:" + port + "/actuator/health"))
                           .GET()
                           .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("UP");
    }

    @Test
    void jacksonBridgeActive() {
        assertThat(objectMapper).isInstanceOf(ObjectMapper.class);
        assertThat(objectMapper.getClass().getName()).startsWith("com.fasterxml.jackson");
    }
}
