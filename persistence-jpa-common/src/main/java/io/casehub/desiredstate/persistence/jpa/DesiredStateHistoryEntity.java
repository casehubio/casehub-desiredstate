package io.casehub.desiredstate.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;

@Entity
@Table(name = "ds_desired_state_history")
@IdClass(DesiredStateHistoryEntity.Key.class)
public class DesiredStateHistoryEntity {

    @Id
    @Column(name = "tenancy_id")
    String tenancyId;

    @Id
    @Column(name = "version")
    long version;

    @Column(name = "graph_json", nullable = false, columnDefinition = "TEXT")
    String graphJson;

    @Column(name = "source", nullable = false)
    String source;

    @Column(name = "initiated_by")
    String initiatedBy;

    @Column(name = "recorded_at", nullable = false)
    Instant recordedAt;

    public record Key(String tenancyId, long version) implements Serializable {}
}
