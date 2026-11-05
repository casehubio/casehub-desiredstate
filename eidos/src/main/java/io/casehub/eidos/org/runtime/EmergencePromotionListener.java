package io.casehub.eidos.org.runtime;

import io.casehub.eidos.org.api.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class EmergencePromotionListener {

    private static final Logger LOG = Logger.getLogger(EmergencePromotionListener.class);

    private final GroupLifecycleService lifecycleService;
    private final OrgRegistry registry;

    @Inject
    public EmergencePromotionListener(GroupLifecycleService lifecycleService, OrgRegistry registry) {
        this.lifecycleService = lifecycleService;
        this.registry = registry;
    }

    public void promote(SwarmTeamFormedData data) {
        String topology = resolveTopology(data.dominantRelation());
        var members = data.memberAgentIds().stream()
            .map(agentId -> new Membership(agentId, null, null))
            .toList();

        var unit = OrganizationalUnit.builder()
            .unitId(data.teamId())
            .name("Emerged: " + data.teamId())
            .tenancyId(data.tenancyId())
            .topology(topology)
            .selfDetermination(0.5)
            .members(members)
            .build();

        registry.registerUnit(unit);

        try {
            lifecycleService.createGroup(data.teamId(), data.tenancyId(), FormationOrigin.EMERGENCE);
        } catch (Exception e) {
            LOG.warnv("Emergence promotion failed for team {0}: {1}", data.teamId(), e.getMessage());
        }
    }

    static String resolveTopology(String dominantRelation) {
        if (dominantRelation == null) return "swarm";
        return switch (dominantRelation.toUpperCase()) {
            case "COMPLEMENTARY" -> "hive";
            case "SHARED_INTEREST" -> "mesh";
            case "COACTIVE", "SHARED_SIGNAL" -> "swarm";
            default -> "swarm";
        };
    }
}
