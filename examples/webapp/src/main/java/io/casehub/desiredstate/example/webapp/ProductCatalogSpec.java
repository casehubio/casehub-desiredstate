package io.casehub.desiredstate.example.webapp;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

@NodeTypeId("product-catalog")
public record ProductCatalogSpec(String name, int maxProducts, String currency) implements NodeSpec {
    @Override
    public NodeType nodeType() { return StoreNodeTypes.PRODUCT_CATALOG; }
}
