package io.casehub.desiredstate.persistence.jpa;

import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.ReconciliationStateStore;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public class SpringJpaReconciliationStateStore implements ReconciliationStateStore {

    private final EntityManager em;
    private final DesiredStateGraphFactory graphFactory;
    private final GraphSerializer serializer = new GraphSerializer();

    public SpringJpaReconciliationStateStore(EntityManager em,
                                              DesiredStateGraphFactory graphFactory) {
        this.em = em;
        this.graphFactory = graphFactory;
    }

    @Override
    @Transactional
    public void store(String tenancyId, DesiredStateGraph lastReconciledDesired) {
        String json = serializer.serialize(lastReconciledDesired);
        ReconciliationStateEntity entity = em.find(ReconciliationStateEntity.class, tenancyId);
        if (entity == null) {
            entity = new ReconciliationStateEntity();
            entity.tenancyId = tenancyId;
            entity.graphJson = json;
            entity.updatedAt = Instant.now();
            em.persist(entity);
        } else {
            entity.graphJson = json;
            entity.updatedAt = Instant.now();
        }
        em.flush();
    }

    @Override
    @Transactional
    public Optional<DesiredStateGraph> load(String tenancyId) {
        ReconciliationStateEntity entity = em.find(ReconciliationStateEntity.class, tenancyId);
        if (entity == null) {
            return Optional.empty();
        }
        DesiredStateGraph graph = serializer.deserialize(entity.graphJson, graphFactory);
        return Optional.ofNullable(graph);
    }

    @Override
    @Transactional
    public void remove(String tenancyId) {
        ReconciliationStateEntity entity = em.find(ReconciliationStateEntity.class, tenancyId);
        if (entity != null) {
            em.remove(entity);
            em.flush();
        }
    }
}
