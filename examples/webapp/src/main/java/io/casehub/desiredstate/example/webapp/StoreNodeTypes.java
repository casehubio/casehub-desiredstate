package io.casehub.desiredstate.example.webapp;

import io.casehub.desiredstate.api.NodeType;

public final class StoreNodeTypes {
    public static final NodeType PRODUCT_CATALOG = NodeType.of("product-catalog");
    public static final NodeType SHOPPING_CART = NodeType.of("shopping-cart");
    public static final NodeType PAYMENT = NodeType.of("payment");
    public static final NodeType FRAUD_CHECK = NodeType.of("fraud-check");
    public static final NodeType ORDER_CONFIRMATION = NodeType.of("order-confirmation");
    public static final NodeType SHIPPING = NodeType.of("shipping");
    public static final NodeType NOTIFICATION = NodeType.of("notification");
    public static final NodeType GIFT_WRAPPING = NodeType.of("gift-wrapping");
    public static final NodeType LOYALTY = NodeType.of("loyalty");
    public static final NodeType FRAUD_REVIEW = NodeType.of("fraud-review");
    public static final NodeType SUPPORT_TICKET = NodeType.of("support-ticket");

    private StoreNodeTypes() {}
}
