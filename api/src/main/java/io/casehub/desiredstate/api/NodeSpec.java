package io.casehub.desiredstate.api;

public interface NodeSpec {
    NodeType nodeType();

    default HumanGating humanGating() {return HumanGating.NONE;}
}
