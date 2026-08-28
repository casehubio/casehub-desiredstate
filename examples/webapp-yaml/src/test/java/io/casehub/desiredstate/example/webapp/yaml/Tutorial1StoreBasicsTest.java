package io.casehub.desiredstate.example.webapp.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.example.webapp.PaymentSpec;
import io.casehub.desiredstate.example.webapp.ProductCatalogSpec;
import io.casehub.desiredstate.example.webapp.ShippingSpec;
import io.casehub.desiredstate.example.webapp.StoreNodeTypes;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.yaml.YamlGraphRecorder;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Tutorial1StoreBasicsTest {

    private static GoalCompiler<Void> compiler;
    private static final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    private static final Map<String, String> TYPE_REGISTRY = Map.ofEntries(
            Map.entry("product-catalog", "io.casehub.desiredstate.example.webapp.ProductCatalogSpec"),
            Map.entry("shopping-cart", "io.casehub.desiredstate.example.webapp.ShoppingCartSpec"),
            Map.entry("payment", "io.casehub.desiredstate.example.webapp.PaymentSpec"),
            Map.entry("order-confirmation", "io.casehub.desiredstate.example.webapp.OrderConfirmationSpec"),
            Map.entry("shipping", "io.casehub.desiredstate.example.webapp.ShippingSpec"));

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadYaml() throws Exception {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = Tutorial1StoreBasicsTest.class.getClassLoader()
                .getResourceAsStream("META-INF/desiredstate/tutorial-1-store-basics.yaml")) {
            assertThat(is).as("Tutorial 1 YAML must be on classpath").isNotNull();
            YamlGraph yamlGraph = yamlMapper.readValue(is, YamlGraph.class);
            YamlGraphRecorder recorder = new YamlGraphRecorder();
            compiler = recorder.createYamlGoalCompiler(
                    TutorialTestHelper.toGraphDescriptor(yamlGraph, TYPE_REGISTRY),
                    TYPE_REGISTRY,
                    yamlGraph.variables() != null ? yamlGraph.variables() : Map.of(),
                    List.of(), yamlGraph).getValue();
        }
    }

    @Test
    void storeHasFiveNodes() {
        DesiredStateGraph graph = compile();
        assertThat(graph.nodes()).hasSize(5);
    }

    @Test
    void orderFlowChain() {
        DesiredStateGraph graph = compile();
        assertThat(graph.dependenciesOf(NodeId.of("shopping-cart")))
                .contains(NodeId.of("product-catalog"));
        assertThat(graph.dependenciesOf(NodeId.of("payment")))
                .contains(NodeId.of("shopping-cart"));
        assertThat(graph.dependenciesOf(NodeId.of("order-confirmation")))
                .contains(NodeId.of("payment"));
        assertThat(graph.dependenciesOf(NodeId.of("shipping")))
                .contains(NodeId.of("order-confirmation"));
    }

    @Test
    void variablesResolvedInSpecs() {
        DesiredStateGraph graph = compile();

        ProductCatalogSpec catalog = (ProductCatalogSpec) graph.nodes()
                .get(NodeId.of("product-catalog")).spec();
        assertThat(catalog.currency()).isEqualTo("USD");

        PaymentSpec payment = (PaymentSpec) graph.nodes()
                .get(NodeId.of("payment")).spec();
        assertThat(payment.provider()).isEqualTo("stripe");
        assertThat(payment.currency()).isEqualTo("USD");

        ShippingSpec shipping = (ShippingSpec) graph.nodes()
                .get(NodeId.of("shipping")).spec();
        assertThat(shipping.carrier()).isEqualTo("fedex");
    }

    @Test
    void productCatalogHasNoUpstreamDependencies() {
        DesiredStateGraph graph = compile();
        assertThat(graph.dependenciesOf(NodeId.of("product-catalog"))).isEmpty();
    }

    @Test
    void allNodesHaveCorrectTypes() {
        DesiredStateGraph graph = compile();
        assertThat(graph.nodes().get(NodeId.of("product-catalog")).type())
                .isEqualTo(StoreNodeTypes.PRODUCT_CATALOG);
        assertThat(graph.nodes().get(NodeId.of("shopping-cart")).type())
                .isEqualTo(StoreNodeTypes.SHOPPING_CART);
        assertThat(graph.nodes().get(NodeId.of("payment")).type())
                .isEqualTo(StoreNodeTypes.PAYMENT);
        assertThat(graph.nodes().get(NodeId.of("order-confirmation")).type())
                .isEqualTo(StoreNodeTypes.ORDER_CONFIRMATION);
        assertThat(graph.nodes().get(NodeId.of("shipping")).type())
                .isEqualTo(StoreNodeTypes.SHIPPING);
    }

    private DesiredStateGraph compile() {
        return ((CompilationResult.SingleGraph) compiler.compile(null, factory)).graph();
    }
}
