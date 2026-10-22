ALTER TABLE ai_proposal DROP CONSTRAINT ai_proposal_resolution_check;
ALTER TABLE ai_proposal ADD CONSTRAINT ai_proposal_resolution_check
    CHECK (resolution IN ('OPEN', 'APPLIED', 'DECLINED'));
ALTER TABLE care_item_history DROP CONSTRAINT care_item_history_operation_check;
ALTER TABLE care_item_history ADD CONSTRAINT care_item_history_operation_check
    CHECK (operation IN ('CREATE', 'UPDATE', 'RESOLVE', 'LINK_EVIDENCE'));
ALTER TABLE content_document DROP CONSTRAINT content_document_kind_check;
ALTER TABLE content_document ADD CONSTRAINT content_document_kind_check
    CHECK (kind IN ('AI_OUTPUT', 'DIRECT_INPUT', 'CONFIRMED_ITEM'));

CREATE TABLE proposal_decision_batch (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES care_space (id),
    decided_by_membership_id UUID NOT NULL,
    decision TEXT NOT NULL CHECK (decision IN ('APPLIED', 'DECLINED')),
    decided_at TIMESTAMPTZ NOT NULL,
    UNIQUE (space_id, id),
    FOREIGN KEY (space_id, decided_by_membership_id) REFERENCES space_membership (space_id, id)
);
CREATE TABLE proposal_decision (
    proposal_id UUID PRIMARY KEY,
    space_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    ordinal_no INTEGER NOT NULL CHECK (ordinal_no >= 0),
    item_id UUID,
    item_version BIGINT CHECK (item_version >= 1),
    document_id UUID,
    UNIQUE (batch_id, ordinal_no),
    FOREIGN KEY (space_id, proposal_id) REFERENCES ai_proposal (space_id, id),
    FOREIGN KEY (space_id, batch_id) REFERENCES proposal_decision_batch (space_id, id),
    FOREIGN KEY (space_id, item_id) REFERENCES care_item (space_id, id),
    FOREIGN KEY (space_id, document_id) REFERENCES content_document (space_id, id),
    CHECK ((item_id IS NULL) = (item_version IS NULL)),
    CHECK ((item_id IS NULL) = (document_id IS NULL))
);
CREATE TABLE care_item_edit_lock (
    item_id UUID PRIMARY KEY,
    space_id UUID NOT NULL,
    holder_membership_id UUID NOT NULL,
    token_digest TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (space_id, item_id) REFERENCES care_item (space_id, id),
    FOREIGN KEY (space_id, holder_membership_id) REFERENCES space_membership (space_id, id)
);

-- Immutable ancestry, including unmodified fields copied from the previous item document.
-- Owner edits of AI output never become independent DIRECT_INPUT evidence.
CREATE TABLE care_item_document_parent (
    space_id UUID NOT NULL,
    document_id UUID NOT NULL,
    parent_document_id UUID NOT NULL,
    PRIMARY KEY (document_id, parent_document_id),
    FOREIGN KEY (space_id, document_id) REFERENCES content_document (space_id, id),
    FOREIGN KEY (space_id, parent_document_id) REFERENCES content_document (space_id, id),
    CHECK (document_id <> parent_document_id)
);
CREATE INDEX care_item_document_parent_reverse_ix ON care_item_document_parent (parent_document_id);

INSERT INTO care_item_document_parent(space_id,document_id,parent_document_id)
SELECT p.space_id,p.output_document_id,i.document_id FROM ai_proposal p JOIN analysis_input_item i ON i.run_id=p.run_id
UNION
SELECT p.space_id,p.output_document_id,b.direct_document_id FROM ai_proposal p
 JOIN analysis_input_basis b ON b.run_id=p.run_id WHERE b.direct_document_id IS NOT NULL;

-- Provenance validity differs from OPEN proposal applicability: applying a proposal changes its
-- captured item's version, but must not erase its decision or its own confirmed document.
CREATE FUNCTION analysis_provenance_valid(wanted_run UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT EXISTS (SELECT 1 FROM analysis_run a WHERE a.id=wanted_run AND a.invalidated_at IS NULL
 AND EXISTS (SELECT 1 FROM analysis_input_source WHERE run_id=a.id)
 AND NOT EXISTS (
   SELECT 1 FROM analysis_input_source s LEFT JOIN source_record r
     ON r.space_id=s.space_id AND r.id=s.source_record_id
   WHERE s.run_id=a.id AND (r.id IS NULL OR r.lifecycle<>'ACTIVE' OR r.version<>s.source_version))
 AND NOT EXISTS (
   SELECT 1 FROM analysis_input_item i LEFT JOIN content_document d
     ON d.space_id=i.space_id AND d.id=i.document_id
   WHERE i.run_id=a.id AND (d.id IS NULL OR d.body IS NULL OR d.redacted_at IS NOT NULL
     OR (a.contract_version='v1' AND (i.source_evidence_ids IS NULL OR EXISTS (
       SELECT 1 FROM unnest(i.source_evidence_ids) captured(evidence_id)
       LEFT JOIN evidence e ON e.space_id=i.space_id AND e.id=captured.evidence_id
       WHERE e.id IS NULL OR e.quote IS NULL OR e.redacted_at IS NOT NULL
         OR NOT EXISTS (SELECT 1 FROM care_item_basis b WHERE b.space_id=i.space_id
           AND b.item_id=i.item_id AND b.source_evidence_id=captured.evidence_id)
         OR NOT EXISTS (SELECT 1 FROM analysis_input_source s WHERE s.space_id=i.space_id
           AND s.run_id=i.run_id AND s.source_record_id=e.record_id AND s.source_version=e.record_version))))))
 AND NOT EXISTS (
   SELECT 1 FROM analysis_input_basis b
   LEFT JOIN care_item_basis live ON live.space_id=b.space_id AND live.id=b.basis_id AND live.item_id=b.item_id
   LEFT JOIN content_document d ON d.space_id=b.space_id AND d.id=b.direct_document_id
   LEFT JOIN evidence e ON e.space_id=b.space_id AND e.id=b.source_evidence_id
   WHERE b.run_id=a.id AND (live.id IS NULL
     OR live.direct_document_id IS DISTINCT FROM b.direct_document_id
     OR live.source_evidence_id IS DISTINCT FROM b.source_evidence_id
     OR (b.direct_document_id IS NOT NULL AND (d.id IS NULL OR d.body IS NULL OR d.redacted_at IS NOT NULL))
     OR (b.source_evidence_id IS NOT NULL AND (e.id IS NULL OR e.quote IS NULL OR e.redacted_at IS NOT NULL
       OR NOT EXISTS (SELECT 1 FROM analysis_input_source s WHERE s.space_id=b.space_id
         AND s.run_id=b.run_id AND s.source_record_id=e.record_id AND s.source_version=e.record_version))))));
$$;

CREATE FUNCTION care_document_available(wanted_document UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
WITH RECURSIVE ancestors(id) AS (
 SELECT wanted_document
 UNION
 SELECT p.parent_document_id FROM care_item_document_parent p JOIN ancestors a ON a.id=p.document_id
)
SELECT NOT EXISTS (
 SELECT 1 FROM ancestors a LEFT JOIN content_document d ON d.id=a.id
 LEFT JOIN ai_proposal p ON p.output_document_id=d.id
 WHERE d.id IS NULL OR d.body IS NULL OR d.redacted_at IS NOT NULL
   OR (p.id IS NOT NULL AND (p.invalidated_at IS NOT NULL OR NOT analysis_provenance_valid(p.run_id)))
);
$$;

ALTER FUNCTION analysis_inputs_valid(UUID) RENAME TO analysis_inputs_current_valid;
CREATE FUNCTION analysis_inputs_valid(wanted_run UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
SELECT analysis_inputs_current_valid(wanted_run) AND NOT EXISTS (
 SELECT 1 FROM analysis_input_item i WHERE i.run_id=wanted_run AND NOT care_document_available(i.document_id)
);
$$;

COMMENT ON TABLE proposal_decision IS 'Immutable decision metadata and references; never stores copied care text or bearer tokens.';
COMMENT ON TABLE care_item_edit_lock IS 'Short editor lease, not approval authority. Hash only; current membership and item availability are checked on every use.';
