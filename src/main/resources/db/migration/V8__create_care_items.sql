-- V7 is reserved by the independent proposal-query work (#21).
ALTER TABLE content_document DROP CONSTRAINT content_document_kind_check;
ALTER TABLE content_document ADD CONSTRAINT content_document_kind_check
    CHECK (kind IN ('AI_OUTPUT', 'DIRECT_INPUT'));

CREATE TABLE care_item (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES care_space (id),
    item_type TEXT NOT NULL CHECK (item_type IN ('SCHEDULE','TASK','QUESTION','OBSERVATION','GUIDANCE')),
    version BIGINT NOT NULL CHECK (version >= 1),
    current_document_id UUID NOT NULL,
    status TEXT,
    visibility TEXT NOT NULL DEFAULT 'VISIBLE' CHECK (visibility IN ('VISIBLE','DELETED')),
    needs_reconfirmation BOOLEAN NOT NULL DEFAULT FALSE,
    needs_conflict_review BOOLEAN NOT NULL DEFAULT FALSE,
    redacted_fields JSONB NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(redacted_fields) = 'array'),
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    create_command_id UUID UNIQUE REFERENCES command_receipt (id) ON DELETE SET NULL,
    UNIQUE (space_id, id),
    FOREIGN KEY (space_id, current_document_id) REFERENCES content_document (space_id, id),
    FOREIGN KEY (space_id, created_by_membership_id) REFERENCES space_membership (space_id, id),
    CHECK ((item_type = 'SCHEDULE' AND status IN ('SCHEDULED','COMPLETED','CANCELED'))
        OR (item_type = 'TASK' AND status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED'))
        OR (item_type = 'QUESTION' AND status IN ('UNCONFIRMED','CONFIRMED'))
        OR (item_type IN ('OBSERVATION','GUIDANCE') AND status IS NULL)),
    CHECK (item_type IN ('OBSERVATION','GUIDANCE') OR status IS NOT NULL)
);
CREATE INDEX care_item_visible_ix ON care_item (space_id, created_at DESC, id DESC)
    WHERE visibility = 'VISIBLE';

CREATE TABLE care_item_basis (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL,
    item_id UUID NOT NULL,
    direct_document_id UUID,
    source_evidence_id UUID,
    submitted_by_membership_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (space_id, item_id) REFERENCES care_item (space_id, id),
    FOREIGN KEY (space_id, direct_document_id) REFERENCES content_document (space_id, id),
    FOREIGN KEY (space_id, source_evidence_id) REFERENCES evidence (space_id, id),
    FOREIGN KEY (space_id, submitted_by_membership_id) REFERENCES space_membership (space_id, id),
    CHECK ((direct_document_id IS NULL) <> (source_evidence_id IS NULL)),
    UNIQUE (item_id, direct_document_id),
    UNIQUE (item_id, source_evidence_id)
);
CREATE TABLE care_item_history (
    space_id UUID NOT NULL,
    item_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 1),
    operation TEXT NOT NULL CHECK (operation = 'CREATE'),
    document_id UUID NOT NULL,
    status TEXT,
    changed_by_membership_id UUID NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (item_id, version),
    FOREIGN KEY (space_id, item_id) REFERENCES care_item (space_id, id),
    FOREIGN KEY (space_id, document_id) REFERENCES content_document (space_id, id),
    FOREIGN KEY (space_id, changed_by_membership_id) REFERENCES space_membership (space_id, id)
);
CREATE TABLE analysis_input_item (
    space_id UUID NOT NULL,
    run_id UUID NOT NULL,
    item_id UUID NOT NULL,
    item_version BIGINT NOT NULL CHECK (item_version >= 1),
    document_id UUID NOT NULL,
    PRIMARY KEY (run_id, item_id),
    FOREIGN KEY (space_id, run_id) REFERENCES analysis_run (space_id, id),
    FOREIGN KEY (space_id, item_id) REFERENCES care_item (space_id, id),
    FOREIGN KEY (space_id, document_id) REFERENCES content_document (space_id, id)
);
COMMENT ON TABLE care_item_basis IS 'Independent direct input or source evidence. Content is held in redactable documents, never command receipts.';
COMMENT ON TABLE care_item_history IS 'Initial CREATE only. Later changes must add their operation and maintain version/document provenance.';
COMMENT ON TABLE analysis_input_item IS 'Candidate item/version/document dependencies. Completion must revalidate them together with source dependencies.';
