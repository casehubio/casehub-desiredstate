package io.casehub.desiredstate.example.webapp.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.example.webapp.NotificationSpec;
import io.casehub.desiredstate.example.webapp.ShippingSpec;
import io.casehub.desiredstate.example.webapp.StoreNodeTypes;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.yaml.YamlGraphRecorder;
import io.casehub.desiredstate.yaml.YamlInvariantConverter;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import io.casehub.desiredstate.yaml.model.YamlModule;
import io.casehub.desiredstate.yaml.model.YamlModuleFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Tutorial3ScaleAndComposeTest {

    private static GoalCompiler<Void> compiler;
    private static final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    private static final Map<String, String> TYPE_REGISTRY = Map.ofEntries(
            Map.entry("product-catalog", "io.casehub.desiredstate.example.webapp.ProductCatalogSpec"),
            Map.entry("shopping-cart", "io.casehub.desiredstate.example.webapp.ShoppingCartSpec"),
            Map.entry("payment", "io.casehub.desiredstate.example.webapp.PaymentSpec"),
            Map.entry("fraud-check", "io.casehub.desiredstate.example.webapp.FraudCheckSpec"),
            Map.entry("order-confirmation", "io.casehub.desiredstate.example.webapp.OrderConfirmationSpec"),
            Map.entry("shipping", "io.casehub.desiredstate.example.webapp.ShippingSpec"),
            Map.entry("notification", "io.casehub.desiredstate.example.webapp.NotificationSpec"));

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadYaml() throws Exception {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

        Map<String, YamlModule> modules = new HashMap<>();
        try (InputStream modIs = Tutorial3ScaleAndComposeTest.class.getClassLoader()
                .getResourceAsStream("META-INF/desiredstate/modules/order-notifications.yaml")) {
            if (modIs != null) {
                YamlModuleFile moduleFile = yamlMapper.readValue(modIs, YamlModuleFile.class);
                YamlModule module = moduleFile.toModule();
                modules.put(module.name(), module);
            }
        }

        try (InputStream is = Tutorial3ScaleAndComposeTest.class.getClassLoader()
                .getResourceAsStream("META-INF/desiredstate/tutorial-3-scale-and-compose.yaml")) {
            assertThat(is).as("Tutorial 3 YAML must be on classpath").isNotNull();
            YamlGraph yamlGraph = yamlMapper.readValue(is, YamlGraph.class);

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
                    invariants, yamlGraph, modules).getValue();
        }
    }

    // ---- forEach: multi-warehouse shipping ----

    @Test
    void forEach_threeShippingNodes_onePerWarehouse() {
        DesiredStateGraph graph = compile();
        assertThat(graph.nodes()).containsKey(NodeId.of("shipping.us-east"));
        assertThat(graph.nodes()).containsKey(NodeId.of("shipping.eu-west"));
        assertThat(graph.nodes()).containsKey(NodeId.of("shipping.ap-south"));
    }

    @Test
    void forEach_eachShippingHasCorrectWarehouse() {
        DesiredStateGraph graph = compile();
        ShippingSpec usEast = (ShippingSpec) graph.nodes()
                .get(NodeId.of("shipping.us-east")).spec();
        assertThat(usEast.warehouse()).isEqualTo("us-east");

        ShippingSpec euWest = (ShippingSpec) graph.nodes()
                .get(NodeId.of("shipping.eu-west")).spec();
        assertThat(euWest.warehouse()).isEqualTo("eu-west");
    }

    @Test
    void forEach_allShippingCopiesDependOnConfirmation() {
        DesiredStateGraph graph = compile();
        assertThat(graph.dependenciesOf(NodeId.of("shipping.us-east")))
                .contains(NodeId.of("order-confirmation"));
        assertThat(graph.dependenciesOf(NodeId.of("shipping.eu-west")))
                .contains(NodeId.of("order-confirmation"));
        assertThat(graph.dependenciesOf(NodeId.of("shipping.ap-south")))
                .contains(NodeId.of("order-confirmation"));
    }

    @Test
    void forEach_templateNodeDoesNotExist() {
        DesiredStateGraph graph = compile();
        assertThat(graph.nodes()).doesNotContainKey(NodeId.of("shipping"));
    }

    // ---- Modules: imported notification pairs ----

    @Test
    void module_paymentAlerts_twoNotifications() {
        DesiredStateGraph graph = compile();
        assertThat(graph.nodes()).containsKey(NodeId.of("payment-alerts.email"));
        assertThat(graph.nodes()).containsKey(NodeId.of("payment-alerts.sms"));
    }

    @Test
    void module_shippingAlerts_twoNotifications() {
        DesiredStateGraph graph = compile();
        assertThat(graph.nodes()).containsKey(NodeId.of("shipping-alerts.email"));
        assertThat(graph.nodes()).containsKey(NodeId.of("shipping-alerts.sms"));
    }

    @Test
    void module_paymentAlerts_dependOnPayment() {
        DesiredStateGraph graph = compile();
        assertThat(graph.dependenciesOf(NodeId.of("payment-alerts.email")))
                .contains(NodeId.of("payment"));
        assertThat(graph.dependenciesOf(NodeId.of("payment-alerts.sms")))
                .contains(NodeId.of("payment"));
    }

    @Test
    void module_shippingAlerts_dependOnConfirmation() {
        DesiredStateGraph graph = compile();
        assertThat(graph.dependenciesOf(NodeId.of("shipping-alerts.email")))
                .contains(NodeId.of("order-confirmation"));
    }

    @Test
    void module_parameterResolved_inSpec() {
        DesiredStateGraph graph = compile();
        NotificationSpec emailSpec = (NotificationSpec) graph.nodes()
                .get(NodeId.of("payment-alerts.email")).spec();
        assertThat(emailSpec.target()).isEqualTo("payment");
        assertThat(emailSpec.channel()).isEqualTo("email");
    }

    // ---- Overall structure ----

    @Test
    void totalNodeCount() {
        DesiredStateGraph graph = compile();
        // 5 fixed nodes (catalog, cart, payment, fraud-check, confirmation)
        // + 3 forEach shipping (us-east, eu-west, ap-south)
        // + 4 module notifications (2 imports × 2 nodes each)
        assertThat(graph.nodes()).hasSize(12);
    }

    private DesiredStateGraph compile() {
        return ((CompilationResult.SingleGraph) compiler.compile(null, factory)).graph();
    }
}
