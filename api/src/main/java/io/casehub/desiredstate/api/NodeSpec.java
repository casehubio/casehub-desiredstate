package io.casehub.desiredstate.api;

public interface NodeSpec {
    default HumanGating humanGating() {return HumanGating.NONE;}
}
