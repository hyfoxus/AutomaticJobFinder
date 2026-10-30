CREATE TABLE source_health (
    source     VARCHAR(64)  PRIMARY KEY,
    state      VARCHAR(16)  NOT NULL,
    since      TIMESTAMPTZ  NOT NULL,
    last_seen  INT          NOT NULL,
    baseline   INT,
    note       VARCHAR(300),
    updated_at TIMESTAMPTZ  NOT NULL
);

ALTER TABLE ingestion_runs ADD COLUMN health VARCHAR(16);

CREATE INDEX idx_ingestion_runs_source_id ON ingestion_runs (source, id);
