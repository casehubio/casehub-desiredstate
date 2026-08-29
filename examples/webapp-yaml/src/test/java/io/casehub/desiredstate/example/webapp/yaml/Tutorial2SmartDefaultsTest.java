package io.casehub.desiredstate.example.webapp.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.InMemoryFaultCountStore;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.example.webapp.FraudReviewSpec;
import io.casehub.desiredstate.example.webapp.NotificationSpec;
import io.casehub.desiredstate.example.webapp.StoreNodeTypes;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.yaml.YamlFaultPolicyBuilder;
import io.casehub.desiredstate.yaml.YamlGraphRecorder;
import io.casehub.desiredstate.yaml.YamlInvariantConverter;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Tutorial2SmartDefaultsTest {

    private static GoalCompiler<Void> compiler;
    private static YamlGraph parsedGraph;
    private static final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    private static final Map<String, String> TYPE_REGISTRY = Map.ofEntries(
            Map.entry("product-catalog", "io.casehub.desiredstate.example.webapp.ProductCatalogSpec"),
            Map.entry("shopping-cart", "io.casehub.desiredstate.example.webapp.ShoppingCartSpec"),
            Map.entry("payment", "io.casehub.desiredstate.example.webapp.PaymentSpec"),
            Map.entry("fraud-check", "io.casehub.desiredstate.example.webapp.FraudCheckSpec"),
            Map.entry("order-confirmation", "io.casehub.desiredstate.example.webapp.OrderConfirmationSpec"),
            Map.entry("shipping", "io.casehub.desiredstate.example.webapp.ShippingSpec"),
            Map.entry("notification", "io.casehub.desiredstate.example.webapp.NotificationSpec"),
            Map.entry("gift-wrapping", "io.casehub.desiredstate.example.webapp.GiftWrappingSpec"),
            Map.entry("loyalty", "io.casehub.desiredstate.example.webapp.LoyaltySpec"),
            Map.entry("fraud-review", "io.casehub.desiredstate.example.webapp.FraudReviewSpec"),
            Map.entry("support-ticket", "io.casehub.desiredstate.example.webapp.SupportTicketSpec"));

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadYaml() throws Exception {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = Tutorial2SmartDefaultsTest.class.getClassLoader()
                .getResourceAsStream("META-INF/desiredstate/tutorial-2-smart-defaults.yaml")) {
            assertThat(is).as("Tutorial 2 YAML must be on classpath").isNotNull();
            YamlGraph yamlGraph = yamlMapper.readValue(is, YamlGraph.class);
            parsedGraph = yamlGraph;

            List<io.casehub.desiredstate.annotations.runtime.ResolvedInvariant> invariants =
                    new ArrayList<>();
            for (var inv : yamlGraph.invariants().entrySet()) {
                invariants.add(YamlInvariantConverter.toDeclarativeInvariant(
                        inv.getKey(), inv.getValue()));
            }

            YamlGraphRecorder recorder = new YamlGraphRecorder();
            compiler = recorder.createYamlGoalCompiler(
                    TutorialTestHelper.toGraphDescriptor(yamlGraph, TYPE_REGISTRY),
                    TYPE_REGISTRY,
                    yamlGraph.variables() != null ? yamlGraph.variables() : Map.of(),
                    invariants, yamlGraph).getValue();
        }
    }

    // ---- Conditional nodes (when:) ----

    @Test
    void giftWrappingIncluded_whenEnabled() {
        DesiredStateGraph graph = compile();
        assertThat(graph.nodes()).containsKey(NodeId.of("gift-wrapping"));
    }

    @Test
    void loyaltyRewardsExcluded_whenDisabled() {
        DesiredStateGraph graph = compile();
        assertThat(graph.nodes()).doesNotContainKey(NodeId.of("loyalty-rewards"));
    }

    // ---- Rules: auto-wiring ----

    @Test
    void rule_autoAddedNotification_forOrderConfirmation() {
        DesiredStateGraph graph = compile();
        assertThat(graph.nodes()).containsKey(NodeId.of("notify-order-confirmation"));
        NotificationSpec spec = (NotificationSpec) graph.nodes()
                .get(NodeId.of("notify-order-confirmation")).spec();
        assertThat(spec.channel()).isEqualTo("email");
        assertThat(spec.target()).isEqualTo("order-confirmation");
    }

    @Test
    void rule_notificationDependsOnConfirmation() {
        DesiredStateGraph graph = compile();
        assertThat(graph.dependenciesOf(NodeId.of("notify-order-confirmation")))
                .contains(NodeId.of("order-confirmation"));
    }

    // ---- Invariants: safety checks ----

    @Test
    void invariant_fraudCheckPresent_buildSucceeds() {
        DesiredStateGraph graph = compile();
        assertThat(graph.nodes()).containsKey(NodeId.of("fraud-check"));
        assertThat(graph.dependenciesOf(NodeId.of("fraud-check")))
                .contains(NodeId.of("payment"));
    }

    // ---- Fault policies: escalation ----

    @Test
    void faultPolicy_paymentFailure_escalatesToFraudReview() {
        assertThat(parsedGraph.faultPolicy()).hasSize(1);
        var policy = YamlFaultPolicyBuilder.build(
                parsedGraph.faultPolicy().getFirst(), TYPE_REGISTRY,
                new InMemoryFaultCountStore());

        DesiredStateGraph graph = compile();
        FaultEvent event = new FaultEvent(
                NodeId.of("payment"), FaultType.PROVISION_FAILED,
                "Card declined");
        ActualState actual = new ActualState(Map.of());

        policy.onFault("store-1", event, graph, actual);
        policy.onFault("store-1", event, graph, actual);
        var mutations = policy.onFault("store-1", event, graph, actual);

        assertThat(mutations).isNotEmpty();
        GraphMutation.AddNode addNode = mutations.stream()
                .filter(m -> m instanceof GraphMutation.AddNode)
                .map(m -> (GraphMutation.AddNode) m)
                .findFirst().orElseThrow();
        assertThat(addNode.node().type()).isEqualTo(StoreNodeTypes.FRAUD_REVIEW);

        FraudReviewSpec reviewSpec = (FraudReviewSpec) addNode.node().spec();
        assertThat(reviewSpec.targetNodeId()).isEqualTo("payment");
        assertThat(reviewSpec.errorDetail()).isEqualTo("Card declined");
    }

    // ---- Overall graph structure ----

    @Test
    void totalNodeCount_withConditionals_andRules() {
        DesiredStateGraph graph = compile();
        // 6 declared (catalog, cart, payment, fraud-check, confirmation, shipping)
        // + 1 conditional (gift-wrapping enabled, loyalty disabled)
        // + 1 rule-generated (notify-order-confirmation)
        assertThat(graph.nodes()).hasSize(8);
    }

    @Test
    void hooks_paymentNodeHasProvisionHooks() {
        DesiredStateGraph graph   = compile();
        var               payment = graph.nodes().get(NodeId.of("payment"));
        assertThat(payment.hooks()).isNotNull();
        assertThat(payment.hooks().provisionPre()).hasSize(1);
        assertThat(payment.hooks().provisionPre().get(0))
                .isInstanceOf(io.casehub.desiredstate.api.LifecycleStep.Verify.class);
        var verify = (io.casehub.desiredstate.api.LifecycleStep.Verify) payment.hooks().provisionPre().get(0);
        assertThat(verify.url()).isEqualTo("http://localhost:5432/health");
        assertThat(verify.timeoutSeconds()).isEqualTo(10);
        assertThat(payment.hooks().provisionPost()).hasSize(1);
        assertThat(payment.hooks().provisionPost().get(0))
                .isInstanceOf(io.casehub.desiredstate.api.LifecycleStep.Notify.class);
    }

    @Test
    void hooks_paymentNodeHasDeprovisionHooks() {
        DesiredStateGraph graph   = compile();
        var               payment = graph.nodes().get(NodeId.of("payment"));
        assertThat(payment.hooks().deprovisionPre()).hasSize(1);
        assertThat(payment.hooks().deprovisionPre().get(0))
                .isInstanceOf(io.casehub.desiredstate.api.LifecycleStep.Wait.class);
        var wait = (io.casehub.desiredstate.api.LifecycleStep.Wait) payment.hooks().deprovisionPre().get(0);
        assertThat(wait.seconds()).isEqualTo(5);
        assertThat(payment.hooks().deprovisionPost()).hasSize(1);
    }

    @Test
    void hooks_nodesWithoutHooks_hooksIsNull() {
        DesiredStateGraph graph   = compile();
        var               catalog = graph.nodes().get(NodeId.of("product-catalog"));
        assertThat(catalog.hooks()).isNull();
        var cart = graph.nodes().get(NodeId.of("shopping-cart"));
        assertThat(cart.hooks()).isNull();
    }


    private DesiredStateGraph compile() {
        return ((CompilationResult.SingleGraph) compiler.compile(null, factory)).graph();
    }
}
