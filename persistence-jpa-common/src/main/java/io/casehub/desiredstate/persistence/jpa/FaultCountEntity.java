package io.casehub.desiredstate.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;

@Entity
@Table(name = "ds_fault_count")
@IdClass(FaultCountEntity.Key.class)
public class FaultCountEntity {

    @Id
    @Column(name = "namespace")
    String namespace;

    @Id
    @Column(name = "tenancy_id")
    String tenancyId;

    @Id
    @Column(name = "node_id")
    String nodeId;

    @Column(name = "count", nullable = false)
    int count;

    public record Key(String namespace, String tenancyId, String nodeId) implements Serializable {}
}
