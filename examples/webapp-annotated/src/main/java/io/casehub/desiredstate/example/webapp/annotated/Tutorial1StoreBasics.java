package io.casehub.desiredstate.example.webapp.annotated;

import io.casehub.desiredstate.annotations.DependsOn;
import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.Node;
import io.casehub.desiredstate.example.webapp.OrderConfirmationSpec;
import io.casehub.desiredstate.example.webapp.PaymentSpec;
import io.casehub.desiredstate.example.webapp.ProductCatalogSpec;
import io.casehub.desiredstate.example.webapp.ShippingSpec;
import io.casehub.desiredstate.example.webapp.ShoppingCartSpec;

// ============================================================================
// Tutorial 1: Store Basics — Your First Desired State (Annotations)
// ============================================================================
//
// This is the annotation equivalent of tutorial-1-store-basics.yaml.
// Each @Node method declares a service. @DependsOn wires the order flow.
//
// Compare with the YAML version:
//   YAML:  type: payment          →  Java: @Node("payment")
//   YAML:  dependsOn: [cart]      →  Java: @DependsOn("shopping-cart")
//   YAML:  spec: { provider: X }  →  Java: return new PaymentSpec("stripe", ...)
//
// The annotation surface is more verbose but gives you full Java type safety
// and IDE autocompletion on spec fields.
// ============================================================================

@DesiredState(namespace = "tutorial", name = "store-basics-annotated")
public interface Tutorial1StoreBasics {

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

    @Node("order-confirmation")
    @DependsOn("payment")
    default OrderConfirmationSpec orderConfirmation() {
        return new OrderConfirmationSpec("order-receipt", true);
    }

    @Node("shipping")
    @DependsOn("order-confirmation")
    default ShippingSpec shipping() {
        return new ShippingSpec("fedex", "us-east-1", true);
    }
}
