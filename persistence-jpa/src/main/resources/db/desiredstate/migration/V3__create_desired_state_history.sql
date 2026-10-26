CREATE TABLE ds_desired_state_history (
    tenancy_id    VARCHAR(255) NOT NULL,
    version       BIGINT NOT NULL,
    graph_json    TEXT NOT NULL,
    source        VARCHAR(255) NOT NULL,
    initiated_by  VARCHAR(255),
    recorded_at   TIMESTAMP NOT NULL,
    PRIMARY KEY (tenancy_id, version)
);
