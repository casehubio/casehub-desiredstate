package io.casehub.desiredstate.persistence.jpa;

import io.casehub.desiredstate.api.FaultCountStore;
import io.casehub.desiredstate.api.NodeId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class JpaFaultCountStore implements FaultCountStore {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public int incrementAndGet(String namespace, String tenancyId, NodeId nodeId) {
        FaultCountEntity.Key key    = new FaultCountEntity.Key(namespace, tenancyId, nodeId.value());
        FaultCountEntity     entity = em.find(FaultCountEntity.class, key);
        if (entity == null) {
            entity           = new FaultCountEntity();
            entity.namespace = namespace;
            entity.tenancyId = tenancyId;
            entity.nodeId    = nodeId.value();
            entity.count     = 1;
            em.persist(entity);
        } else {
            entity.count++;
        }
        em.flush();
        return entity.count;
    }

    @Override
    public int getCount(String namespace, String tenancyId, NodeId nodeId) {
        FaultCountEntity entity = em.find(FaultCountEntity.class,
                                          new FaultCountEntity.Key(namespace, tenancyId, nodeId.value()));
        return entity != null ? entity.count : 0;
    }

    @Override
    @Transactional
    public void reset(String namespace, String tenancyId, NodeId nodeId) {
        FaultCountEntity.Key key    = new FaultCountEntity.Key(namespace, tenancyId, nodeId.value());
        FaultCountEntity     entity = em.find(FaultCountEntity.class, key);
        if (entity == null) {
            entity           = new FaultCountEntity();
            entity.namespace = namespace;
            entity.tenancyId = tenancyId;
            entity.nodeId    = nodeId.value();
            entity.count     = 0;
            em.persist(entity);
        } else {
            entity.count = 0;
        }
    }

    @Override
    @Transactional
    public void remove(String namespace, String tenancyId, NodeId nodeId) {
        FaultCountEntity entity = em.find(FaultCountEntity.class,
                                          new FaultCountEntity.Key(namespace, tenancyId, nodeId.value()));
        if (entity != null) {
            em.remove(entity);
        }
    }

    @Override
    @Transactional
    public void evict(String namespace, String tenancyId, Set<NodeId> retainedNodes) {
        if (retainedNodes.isEmpty()) {
            em.createQuery("DELETE FROM FaultCountEntity e WHERE e.namespace = :ns AND e.tenancyId = :tid")
              .setParameter("ns", namespace)
              .setParameter("tid", tenancyId)
              .executeUpdate();
        } else {
            Set<String> retained = retainedNodes.stream()
                                                .map(NodeId::value)
                                                .collect(Collectors.toSet());
            em.createQuery("DELETE FROM FaultCountEntity e WHERE e.namespace = :ns AND e.tenancyId = :tid AND e.nodeId NOT IN :retained")
              .setParameter("ns", namespace)
              .setParameter("tid", tenancyId)
              .setParameter("retained", retained)
              .executeUpdate();
        }
    }
}
