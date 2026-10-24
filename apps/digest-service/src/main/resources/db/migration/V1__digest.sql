-- Runs in schema "digest" (spring.flyway.schemas / default-schema), with its own history table.

-- Jobs already mailed. Written after the SMTP server accepted the mail.
create table sent_assessment (
    id            bigserial   primary key,
    assessment_id bigint      not null,
    sent_at       timestamptz not null,
    digest_id     uuid        not null,
    constraint uq_sent_assessment unique (assessment_id)
);

-- Where the scan of the scoring API continues: (scored_at, after_id) in scoring's order. One row.
create table scan_cursor (
    id        smallint    primary key check (id = 1),
    scored_at timestamptz not null,
    after_id  bigint      not null
);
