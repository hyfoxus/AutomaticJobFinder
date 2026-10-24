-- Serves the digest's "newly scored" scan: GET /api/assessments?scoredSince=, ordered by scored_at, id.
create index idx_assessments_scored_at on assessments (scored_at, id) where status = 'SCORED';
