package io.casehub.desiredstate.example.webapp.annotated;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.example.webapp.NotificationSpec;
import io.casehub.desiredstate.example.webapp.ShippingSpec;
import io.casehub.desiredstate.example.webapp.StoreNodeTypes;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Tutorial3ScaleAndComposeTest {

    private static DesiredStateGraph graph;

    @BeforeAll
    static void compile() {
        var compiler = new Tutorial3ScaleAndCompose();
        var result = compiler.compile(null, new DefaultDesiredStateGraphFactory());
        graph = ((CompilationResult.SingleGraph) result).graph();
    }

    // ---- forEach equivalent: multi-warehouse shipping ----

    @Test
    void threeShippingNodes() {
        assertThat(graph.nodes()).containsKey(NodeId.of("shipping.us-east"));
        assertThat(graph.nodes()).containsKey(NodeId.of("shipping.eu-west"));
        assertThat(graph.nodes()).containsKey(NodeId.of("shipping.ap-south"));
    }

    @Test
    void eachShippingHasCorrectWarehouse() {
        ShippingSpec usEast = (ShippingSpec) graph.nodes()
                .get(NodeId.of("shipping.us-east")).spec();
        assertThat(usEast.warehouse()).isEqualTo("us-east");

        ShippingSpec euWest = (ShippingSpec) graph.nodes()
                .get(NodeId.of("shipping.eu-west")).spec();
        assertThat(euWest.warehouse()).isEqualTo("eu-west");
    }

    @Test
    void allShippingDependOnConfirmation() {
        assertThat(graph.dependenciesOf(NodeId.of("shipping.us-east")))
                .contains(NodeId.of("order-confirmation"));
        assertThat(graph.dependenciesOf(NodeId.of("shipping.eu-west")))
                .contains(NodeId.of("order-confirmation"));
        assertThat(graph.dependenciesOf(NodeId.of("shipping.ap-south")))
                .contains(NodeId.of("order-confirmation"));
    }

    // ---- Module equivalent: notification pairs ----

    @Test
    void paymentAlerts_twoNotifications() {
        assertThat(graph.nodes()).containsKey(NodeId.of("payment-alerts.email"));
        assertThat(graph.nodes()).containsKey(NodeId.of("payment-alerts.sms"));
    }

    @Test
    void shippingAlerts_twoNotifications() {
        assertThat(graph.nodes()).containsKey(NodeId.of("shipping-alerts.email"));
        assertThat(graph.nodes()).containsKey(NodeId.of("shipping-alerts.sms"));
    }

    @Test
    void paymentAlerts_dependOnPayment() {
        assertThat(graph.dependenciesOf(NodeId.of("payment-alerts.email")))
                .contains(NodeId.of("payment"));
        assertThat(graph.dependenciesOf(NodeId.of("payment-alerts.sms")))
                .contains(NodeId.of("payment"));
    }

    @Test
    void parameterResolved_inSpec() {
        NotificationSpec emailSpec = (NotificationSpec) graph.nodes()
                .get(NodeId.of("payment-alerts.email")).spec();
        assertThat(emailSpec.target()).isEqualTo("payment");
        assertThat(emailSpec.channel()).isEqualTo("email");
    }

    // ---- Matches YAML tutorial output ----

    @Test
    void totalNodeCount_matchesYamlTutorial() {
        assertThat(graph.nodes()).hasSize(12);
    }

    @Test
    void orderFlowChain() {
        assertThat(graph.dependenciesOf(NodeId.of("shopping-cart")))
                .contains(NodeId.of("product-catalog"));
        assertThat(graph.dependenciesOf(NodeId.of("payment")))
                .contains(NodeId.of("shopping-cart"));
        assertThat(graph.dependenciesOf(NodeId.of("order-confirmation")))
                .contains(NodeId.of("fraud-check"));
    }
}
