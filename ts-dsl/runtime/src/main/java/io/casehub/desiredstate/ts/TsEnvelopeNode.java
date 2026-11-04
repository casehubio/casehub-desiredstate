package io.casehub.desiredstate.ts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.TargetStatus;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TsEnvelopeNode(
        String id,
        String type,
        Map<String, Object> spec,
        HumanGating humanGating,
        Map<String, Object> hooks,
        TargetStatus targetStatus) {

    public TsEnvelopeNode(String id, String type, Map<String, Object> spec,
                          HumanGating humanGating, Map<String, Object> hooks) {
        this(id, type, spec, humanGating, hooks, null);
    }

    public TsEnvelopeNode {
        if (humanGating == null) {humanGating = HumanGating.NONE;}
        if (spec == null) {spec = Map.of();}
        if (targetStatus == null) {targetStatus = TargetStatus.ACTIVE;}
    }
}
