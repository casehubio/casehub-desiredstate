package io.casehub.desiredstate.example.webapp;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

@NodeTypeId("gift-wrapping")
public record GiftWrappingSpec(String style, double surcharge) implements NodeSpec {
    @Override
    public NodeType nodeType() { return StoreNodeTypes.GIFT_WRAPPING; }
}
