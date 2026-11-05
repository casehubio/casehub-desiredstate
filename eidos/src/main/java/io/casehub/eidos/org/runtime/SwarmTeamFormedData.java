package io.casehub.eidos.org.runtime;

import java.util.List;
import java.util.Objects;

public record SwarmTeamFormedData(
    String teamId,
    String tenancyId,
    List<String> memberAgentIds,
    String dominantRelation
) {
    public SwarmTeamFormedData {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(tenancyId, "tenancyId");
        memberAgentIds = memberAgentIds != null ? List.copyOf(memberAgentIds) : List.of();
    }
}
