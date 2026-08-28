package io.casehub.desiredstate.example.webapp.annotated;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.example.webapp.FraudCheckSpec;
import io.casehub.desiredstate.example.webapp.NotificationSpec;
import io.casehub.desiredstate.example.webapp.OrderConfirmationSpec;
import io.casehub.desiredstate.example.webapp.PaymentSpec;
import io.casehub.desiredstate.example.webapp.ProductCatalogSpec;
import io.casehub.desiredstate.example.webapp.ShippingSpec;
import io.casehub.desiredstate.example.webapp.ShoppingCartSpec;

import java.util.ArrayList;
import java.util.List;

// ============================================================================
// Tutorial 3: Scale & Compose — forEach and Modules (Java GoalCompiler)
// ============================================================================
//
// forEach and modules are YAML-only language features. In Java, you achieve
// the same result with a programmatic GoalCompiler that builds the graph
// in code.
//
// This is deliberately more verbose than the YAML version — that's the point.
// The YAML surface exists so operators don't have to write this Java code.
//
// Compare:
//   YAML forEach:     3 lines → stamps 3 shipping nodes automatically
//   Java GoalCompiler: explicit loop creating each node and dependency
//
//   YAML module import: 4 lines → injects email + SMS with aliased IDs
//   Java GoalCompiler: explicit helper method creating notification pairs
// ============================================================================

public class Tutorial3ScaleAndCompose implements GoalCompiler<Void> {

    private static final List<String> WAREHOUSES = List.of("us-east", "eu-west", "ap-south");

    @Override
    public CompilationResult compile(Void goals, DesiredStateGraphFactory factory) {
        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency> deps = new ArrayList<>();

        // ---- Fixed nodes (same as tutorials 1-2) ----

        nodes.add(node("product-catalog",
                new ProductCatalogSpec("Main Catalog", 10000, "USD")));

        nodes.add(node("shopping-cart",
                new ShoppingCartSpec(30, 50)));
        deps.add(dep("shopping-cart", "product-catalog"));

        nodes.add(node("payment",
                new PaymentSpec("stripe", "USD", 3)));
        deps.add(dep("payment", "shopping-cart"));

        nodes.add(node("fraud-check",
                new FraudCheckSpec(0.7, "sift")));
        deps.add(dep("fraud-check", "payment"));

        nodes.add(node("order-confirmation",
                new OrderConfirmationSpec("order-receipt", true)));
        deps.add(dep("order-confirmation", "fraud-check"));

        // ---- forEach equivalent: one shipping handler per warehouse ----
        // In YAML this is 6 lines. In Java, it's an explicit loop.

        for (String warehouse : WAREHOUSES) {
            String id = "shipping." + warehouse;
            nodes.add(node(id,
                    new ShippingSpec("fedex", warehouse, true)));
            deps.add(dep(id, "order-confirmation"));
        }

        // ---- Module equivalent: notification pairs ----
        // In YAML this is two import blocks. In Java, it's a helper method.

        addNotificationPair("payment-alerts", "payment", nodes, deps);
        addNotificationPair("shipping-alerts", "order-confirmation", nodes, deps);

        return CompilationResult.single(factory.of(nodes, deps));
    }

    private static void addNotificationPair(String alias, String watchedStep,
            List<DesiredNode> nodes, List<Dependency> deps) {
        nodes.add(node(alias + ".email",
                new NotificationSpec("email", watchedStep)));
        deps.add(dep(alias + ".email", watchedStep));

        nodes.add(node(alias + ".sms",
                new NotificationSpec("sms", watchedStep)));
        deps.add(dep(alias + ".sms", watchedStep));
    }

    private static DesiredNode node(String id, io.casehub.desiredstate.api.NodeSpec spec) {
        return new DesiredNode(NodeId.of(id), spec, HumanGating.NONE);
    }

    private static Dependency dep(String from, String to) {
        return new Dependency(NodeId.of(from), NodeId.of(to));
    }
}
