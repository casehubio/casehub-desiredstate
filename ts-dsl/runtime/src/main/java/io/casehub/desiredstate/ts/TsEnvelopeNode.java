package io.casehub.desiredstate.ts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.casehub.desiredstate.api.HumanGating;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TsEnvelopeNode(
        String id,
        String type,
        Map<String, Object> spec,
        HumanGating humanGating,
        Map<String, Object> hooks) {

    public TsEnvelopeNode {
        if (humanGating == null) humanGating = HumanGating.NONE;
        if (spec == null) spec = Map.of();
    }
}
