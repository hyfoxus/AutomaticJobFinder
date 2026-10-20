-- Where the last pull from ingestion ended: the largest first_seen_at of a chunk that was handed to scoring.
create table ingest_cursor (
    id                 smallint    primary key check (id = 1),
    last_first_seen_at timestamptz not null,
    updated_at         timestamptz not null
);
