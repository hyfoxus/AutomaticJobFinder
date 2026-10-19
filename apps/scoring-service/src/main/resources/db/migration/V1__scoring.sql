-- Runs in schema "scoring" (spring.flyway.schemas / default-schema), with its own history table.
create table assessments (
    id               bigserial primary key,
    source           varchar(64)  not null,
    external_id      varchar(255) not null,
    title            text         not null,
    company          text,
    location         text,
    url              text         not null,
    description      text,
    posted_at        timestamptz,
    first_seen_at    timestamptz  not null,
    status           varchar(32)  not null,
    attempts         integer      not null default 0,
    tier             varchar(16),
    interview_chance varchar(16),
    reason           text,
    met_must_haves   jsonb,
    gaps             jsonb,
    model            varchar(200),
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null,
    scored_at        timestamptz,
    constraint uq_assessments_job unique (source, external_id)
);

create index idx_assessments_status_seen on assessments (status, first_seen_at desc);

-- One row per model call, so a day's spend is a plain SUM.
create table llm_usage (
    id            bigserial primary key,
    ts            timestamptz  not null,
    day           date         not null,
    model         varchar(200) not null,
    input_tokens  bigint       not null,
    output_tokens bigint       not null,
    micros        bigint       not null,
    estimated     boolean      not null,
    job_key       varchar(400) not null
);

create index idx_llm_usage_day on llm_usage (day);
