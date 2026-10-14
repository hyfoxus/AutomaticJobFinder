CREATE TABLE jobs (
    id            BIGSERIAL PRIMARY KEY,
    source        VARCHAR(64)   NOT NULL,
    external_id   VARCHAR(255)  NOT NULL,
    title         VARCHAR(512)  NOT NULL,
    company       VARCHAR(512),
    location      VARCHAR(512),
    url           VARCHAR(2048) NOT NULL,
    description   TEXT,
    posted_at     TIMESTAMPTZ,
    first_seen_at TIMESTAMPTZ   NOT NULL,
    last_seen_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_jobs_source_external UNIQUE (source, external_id)
);

CREATE INDEX idx_jobs_first_seen ON jobs (first_seen_at, id);

CREATE TABLE ingestion_runs (
    id          BIGSERIAL PRIMARY KEY,
    source      VARCHAR(64)  NOT NULL,
    started_at  TIMESTAMPTZ  NOT NULL,
    finished_at TIMESTAMPTZ  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    fetched     INT          NOT NULL DEFAULT 0,
    inserted    INT          NOT NULL DEFAULT 0,
    updated     INT          NOT NULL DEFAULT 0,
    skipped     INT          NOT NULL DEFAULT 0,
    error       VARCHAR(2000)
);
