CREATE TABLE ds_fault_count (
    namespace    VARCHAR(255) NOT NULL,
    tenancy_id   VARCHAR(255) NOT NULL,
    node_id      VARCHAR(255) NOT NULL,
    count        INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (namespace, tenancy_id, node_id)
);
