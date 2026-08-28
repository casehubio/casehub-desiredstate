package io.casehub.desiredstate.example.webapp.annotated;

import io.casehub.desiredstate.annotations.DependsOn;
import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.DirectDep;
import io.casehub.desiredstate.annotations.FaultPolicyDef;
import io.casehub.desiredstate.annotations.GraphInvariant;
import io.casehub.desiredstate.annotations.GraphRule;
import io.casehub.desiredstate.annotations.Match;
import io.casehub.desiredstate.annotations.Node;
import io.casehub.desiredstate.annotations.NotExists;
import io.casehub.desiredstate.annotations.Tier;
import io.casehub.desiredstate.annotations.runtime.Direction;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.GraphMutations;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.example.webapp.FraudCheckSpec;
import io.casehub.desiredstate.example.webapp.FraudReviewSpec;
import io.casehub.desiredstate.example.webapp.GiftWrappingSpec;
import io.casehub.desiredstate.example.webapp.NotificationSpec;
import io.casehub.desiredstate.example.webapp.OrderConfirmationSpec;
import io.casehub.desiredstate.example.webapp.PaymentSpec;
import io.casehub.desiredstate.example.webapp.ProductCatalogSpec;
import io.casehub.desiredstate.example.webapp.ShippingSpec;
import io.casehub.desiredstate.example.webapp.ShoppingCartSpec;
import io.casehub.desiredstate.example.webapp.SupportTicketSpec;

import java.util.List;

// ============================================================================
// Tutorial 2: Smart Defaults — Invariants, Rules & Fault Policies (Annotations)
// ============================================================================
//
// This is the annotation equivalent of tutorial-2-smart-defaults.yaml.
//
// Key differences from YAML:
//
//   YAML when: → No annotation equivalent. Conditional inclusion is handled
//     by the GoalCompiler method that constructs the graph — you simply
//     don't call the node method when the feature is disabled.
//
//   YAML invariants: → @GraphInvariant on a static void method with
//     pattern parameters. If the pattern can't bind, it's a violation.
//
//   YAML rules: → @GraphRule on a static method returning List<GraphMutation>.
//     Pattern parameters bind matched nodes; the method returns mutations.
//
//   YAML faultPolicy: → @FaultPolicyDef on the class with @Tier thresholds.
//     Review spec factories are default methods that receive FaultEvent.
// ============================================================================

@DesiredState(namespace = "tutorial", name = "smart-defaults-annotated")
@FaultPolicyDef(
        faultTypes = {"PROVISION_FAILED"},
        nodeTypes = {"payment"},
        tiers = {
                @Tier(threshold = 3, review = "createFraudReview", nodeType = "fraud-review"),
                @Tier(threshold = 5, review = "createSupportTicket", nodeType = "support-ticket")
        }
)
public interface Tutorial2SmartDefaults {

    // ---- Core order flow (same as Tutorial 1) ----

    @Node("product-catalog")
    default ProductCatalogSpec productCatalog() {
        return new ProductCatalogSpec("Main Catalog", 10000, "USD");
    }

    @Node("shopping-cart")
    @DependsOn("product-catalog")
    default ShoppingCartSpec shoppingCart() {
        return new ShoppingCartSpec(30, 50);
    }

    @Node("payment")
    @DependsOn("shopping-cart")
    default PaymentSpec payment() {
        return new PaymentSpec("stripe", "USD", 3);
    }

    @Node("fraud-check")
    @DependsOn("payment")
    default FraudCheckSpec fraudCheck() {
        return new FraudCheckSpec(0.7, "sift");
    }

    @Node("order-confirmation")
    @DependsOn("fraud-check")
    default OrderConfirmationSpec orderConfirmation() {
        return new OrderConfirmationSpec("order-receipt", true);
    }

    @Node("shipping")
    @DependsOn("order-confirmation")
    default ShippingSpec shipping() {
        return new ShippingSpec("fedex", "us-east-1", true);
    }

    // ---- Conditional node: gift wrapping ----
    // No @Node — included conditionally by the GoalCompiler method.
    // In the YAML version this uses when:. In annotations, the GoalCompiler
    // controls which nodes to include.
    @Node("gift-wrapping")
    @DependsOn("shopping-cart")
    default GiftWrappingSpec giftWrapping() {
        return new GiftWrappingSpec("premium", 4.99);
    }

    // ---- Invariant: every payment must have fraud detection ----
    // If someone removes the fraud-check node, the build fails.

    @GraphInvariant
    static void paymentRequiresFraudCheck(
            @Match(type = "payment") DesiredNode pay,
            @DirectDep(type = "fraud-check", of = "pay",
                    direction = Direction.DEPENDENTS) DesiredNode fraud) {
    }

    // ---- Rule: auto-add notification for every order confirmation ----
    // If a confirmation node doesn't have a notification dependent, add one.

    @GraphRule
    static List<GraphMutation> autoNotifyConfirmations(
            @Match(type = "order-confirmation") DesiredNode confirm,
            @NotExists(type = "notification", of = "confirm",
                    direction = Direction.DEPENDENTS) Void guard) {
        return GraphMutations.addNodeDependingOn(
                new DesiredNode(
                        NodeId.of("notify-" + confirm.id().value()),
                        new NotificationSpec("email", confirm.id().value()),
                        HumanGating.NONE),
                confirm.id());
    }

    // ---- Fault policy: review spec factories ----

    default FraudReviewSpec createFraudReview(FaultEvent event, DesiredStateGraph graph) {
        return new FraudReviewSpec(event.node().value(), event.detail());
    }

    default SupportTicketSpec createSupportTicket(FaultEvent event, DesiredStateGraph graph) {
        return new SupportTicketSpec(event.node().value(), event.detail(), "high");
    }
}
