package io.casehub.desiredstate.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ds_reconciliation_state")
public class ReconciliationStateEntity {

    @Id
    @Column(name = "tenancy_id")
    String tenancyId;

    @Column(name = "graph_json", nullable = false, columnDefinition = "TEXT")
    String graphJson;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;
}
