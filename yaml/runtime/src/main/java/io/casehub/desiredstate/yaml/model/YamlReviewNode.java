package io.casehub.desiredstate.yaml.model;

import io.casehub.desiredstate.api.HumanGating;

import java.util.Map;

public record YamlReviewNode(
        String type,
        Map<String, Object> spec,
        HumanGating humanGating) {

    public YamlReviewNode {
        spec = spec != null ? spec : Map.of();
        humanGating = humanGating != null ? humanGating : HumanGating.NONE;
    }
}
