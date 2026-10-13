CREATE TABLE analysis_run (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL,
    record_id UUID NOT NULL,
    record_version BIGINT NOT NULL CHECK (record_version >= 1),
    attempt_no BIGINT NOT NULL CHECK (attempt_no >= 1),
    request_id UUID NOT NULL UNIQUE,
    input_recorded_at TIMESTAMPTZ NOT NULL,
    timezone TEXT NOT NULL CHECK (timezone = 'Asia/Seoul'),
    requested_by_membership_id UUID NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'DISCARDED')),
    failure_code TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    retry_of_id UUID UNIQUE,
    command_id UUID UNIQUE REFERENCES command_receipt (id) ON DELETE SET NULL,
    worker_token UUID,
    lease_until TIMESTAMPTZ,
    UNIQUE (space_id, id),
    UNIQUE (space_id, record_id, id),
    UNIQUE (record_id, attempt_no),
    FOREIGN KEY (space_id, record_id) REFERENCES source_record (space_id, id),
    FOREIGN KEY (space_id, requested_by_membership_id) REFERENCES space_membership (space_id, id),
    FOREIGN KEY (space_id, record_id, retry_of_id) REFERENCES analysis_run (space_id, record_id, id),
    CHECK ((status = 'RUNNING' AND worker_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'RUNNING' AND worker_token IS NULL AND lease_until IS NULL)),
    CHECK ((status IN ('COMPLETED', 'FAILED', 'DISCARDED')) = (finished_at IS NOT NULL)),
    CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)),
    CHECK (status <> 'QUEUED' OR started_at IS NULL),
    CHECK (status NOT IN ('RUNNING', 'COMPLETED') OR started_at IS NOT NULL),
    CHECK (invalidated_at IS NULL OR status = 'DISCARDED')
);
CREATE UNIQUE INDEX analysis_initial_uq ON analysis_run (record_id) WHERE retry_of_id IS NULL;
CREATE UNIQUE INDEX analysis_pending_uq ON analysis_run (record_id) WHERE status IN ('QUEUED', 'RUNNING');
CREATE UNIQUE INDEX analysis_completed_uq ON analysis_run (record_id) WHERE status = 'COMPLETED';
CREATE INDEX analysis_queue_ix ON analysis_run (created_at, id) WHERE status = 'QUEUED';
CREATE INDEX analysis_lease_ix ON analysis_run (lease_until) WHERE status = 'RUNNING';

-- No source text is copied into execution metadata or command receipts.
CREATE TABLE analysis_input_source (
    space_id UUID NOT NULL,
    run_id UUID NOT NULL,
    source_record_id UUID NOT NULL,
    source_version BIGINT NOT NULL CHECK (source_version >= 1),
    PRIMARY KEY (run_id, source_record_id),
    FOREIGN KEY (space_id, run_id) REFERENCES analysis_run (space_id, id),
    FOREIGN KEY (space_id, source_record_id) REFERENCES source_record (space_id, id)
);
CREATE INDEX analysis_input_source_record_ix ON analysis_input_source (space_id, source_record_id);

CREATE TABLE content_document (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES care_space (id),
    kind TEXT NOT NULL CHECK (kind = 'AI_OUTPUT'),
    body JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    redacted_at TIMESTAMPTZ,
    UNIQUE (space_id, id),
    CHECK ((body IS NULL) = (redacted_at IS NOT NULL)),
    CHECK (body IS NULL OR jsonb_typeof(body) = 'object')
);
CREATE TABLE evidence (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL,
    record_id UUID NOT NULL,
    record_version BIGINT NOT NULL CHECK (record_version >= 1),
    quote TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    redacted_at TIMESTAMPTZ,
    UNIQUE (space_id, id),
    FOREIGN KEY (space_id, record_id) REFERENCES source_record (space_id, id),
    CHECK ((quote IS NULL) = (redacted_at IS NOT NULL))
);
CREATE TABLE ai_proposal (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL,
    run_id UUID NOT NULL,
    ordinal_no INTEGER NOT NULL CHECK (ordinal_no >= 0),
    ai_operation TEXT NOT NULL CHECK (ai_operation = 'CREATE'),
    item_type TEXT NOT NULL CHECK (item_type IN ('SCHEDULE', 'TASK', 'QUESTION', 'OBSERVATION', 'GUIDANCE')),
    output_document_id UUID NOT NULL UNIQUE,
    primary_evidence_id UUID NOT NULL UNIQUE,
    resolution TEXT NOT NULL CHECK (resolution = 'OPEN'),
    created_at TIMESTAMPTZ NOT NULL,
    invalidated_at TIMESTAMPTZ,
    UNIQUE (space_id, id),
    UNIQUE (run_id, ordinal_no),
    FOREIGN KEY (space_id, run_id) REFERENCES analysis_run (space_id, id),
    FOREIGN KEY (space_id, output_document_id) REFERENCES content_document (space_id, id),
    FOREIGN KEY (space_id, primary_evidence_id) REFERENCES evidence (space_id, id)
);

COMMENT ON TABLE analysis_run IS 'Durable execution queue. Retry creates a new attempt; no automatic AI redelivery or retention TTL. No historical source backfill.';
COMMENT ON COLUMN analysis_run.lease_until IS 'Execution ownership expiry supplied by explicit worker configuration; not an API retry/usage policy.';
COMMENT ON TABLE analysis_input_source IS 'Source/version provenance for every input to a run. Future candidate summaries must also register their source dependencies.';
COMMENT ON TABLE ai_proposal IS 'Unconfirmed CREATE proposals and source evidence only. CareItem candidate lookup and UPDATE/RESOLVE persistence require a subsequent migration and service integration.';
