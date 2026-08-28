package io.casehub.desiredstate.example.webapp;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

@NodeTypeId("shopping-cart")
public record ShoppingCartSpec(int sessionTimeoutMinutes, int maxItems) implements NodeSpec {
    @Override
    public NodeType nodeType() { return StoreNodeTypes.SHOPPING_CART; }
}
