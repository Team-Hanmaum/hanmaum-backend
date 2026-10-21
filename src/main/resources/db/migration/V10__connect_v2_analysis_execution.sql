ALTER TABLE analysis_run ADD COLUMN contract_version TEXT NOT NULL DEFAULT 'v1'
    CHECK (contract_version IN ('v1', 'v2'));
ALTER TABLE analysis_run ADD COLUMN input_captured BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE analysis_input_item ADD COLUMN item_type TEXT;
ALTER TABLE analysis_input_item ADD COLUMN item_status TEXT;

-- Captured link IDs deliberately have no FK to the live link: detachment must remain detectable.
CREATE TABLE analysis_input_basis (
    space_id UUID NOT NULL,
    run_id UUID NOT NULL,
    item_id UUID NOT NULL,
    basis_id UUID NOT NULL,
    direct_document_id UUID,
    source_evidence_id UUID,
    PRIMARY KEY (run_id, basis_id),
    FOREIGN KEY (run_id, item_id) REFERENCES analysis_input_item (run_id, item_id),
    FOREIGN KEY (space_id, run_id) REFERENCES analysis_run (space_id, id),
    CHECK ((direct_document_id IS NULL) <> (source_evidence_id IS NULL))
);
CREATE INDEX analysis_input_basis_document_ix ON analysis_input_basis (space_id, direct_document_id);

ALTER TABLE ai_proposal DROP CONSTRAINT ai_proposal_ai_operation_check;
ALTER TABLE ai_proposal ADD CONSTRAINT ai_proposal_ai_operation_check
    CHECK (ai_operation IN ('CREATE', 'UPDATE', 'RESOLVE', 'LINK_EVIDENCE'));

-- One predicate for completion and public reads. No source text is copied into metadata.
CREATE FUNCTION analysis_inputs_valid(wanted_run UUID) RETURNS BOOLEAN
LANGUAGE SQL STABLE AS $$
SELECT EXISTS (
 SELECT 1 FROM analysis_run a WHERE a.id = wanted_run
 AND (a.contract_version = 'v1' OR a.input_captured OR a.status = 'QUEUED')
 AND EXISTS (SELECT 1 FROM analysis_input_source WHERE run_id = a.id)
 AND NOT EXISTS (
   SELECT 1 FROM analysis_input_source dep
   LEFT JOIN source_record r ON r.space_id=dep.space_id AND r.id=dep.source_record_id
   WHERE dep.run_id=a.id AND (r.id IS NULL OR r.lifecycle<>'ACTIVE' OR r.version<>dep.source_version))
 AND NOT EXISTS (
   SELECT 1 FROM analysis_input_item dep
   LEFT JOIN care_item i ON i.space_id=dep.space_id AND i.id=dep.item_id
   LEFT JOIN content_document d ON d.space_id=dep.space_id AND d.id=dep.document_id
   WHERE dep.run_id=a.id AND (i.id IS NULL OR i.visibility<>'VISIBLE' OR i.needs_reconfirmation
     OR i.version<>dep.item_version OR i.current_document_id<>dep.document_id
     OR d.body IS NULL OR d.redacted_at IS NOT NULL
     OR (a.contract_version='v2' AND (i.item_type IS DISTINCT FROM dep.item_type
       OR i.status IS DISTINCT FROM dep.item_status))
     OR (a.contract_version='v1' AND (dep.source_evidence_ids IS NULL OR EXISTS (
       SELECT 1 FROM unnest(dep.source_evidence_ids) captured(evidence_id)
       LEFT JOIN evidence e ON e.space_id=dep.space_id AND e.id=captured.evidence_id
       WHERE e.id IS NULL OR e.quote IS NULL OR e.redacted_at IS NOT NULL
         OR NOT EXISTS (SELECT 1 FROM care_item_basis b WHERE b.space_id=dep.space_id
           AND b.item_id=dep.item_id AND b.source_evidence_id=captured.evidence_id)
         OR NOT EXISTS (SELECT 1 FROM analysis_input_source s WHERE s.space_id=dep.space_id
           AND s.run_id=dep.run_id AND s.source_record_id=e.record_id AND s.source_version=e.record_version))))))
 AND NOT EXISTS (
   SELECT 1 FROM analysis_input_basis dep
   LEFT JOIN care_item_basis b ON b.space_id=dep.space_id AND b.item_id=dep.item_id AND b.id=dep.basis_id
   LEFT JOIN content_document d ON d.space_id=dep.space_id AND d.id=dep.direct_document_id
   LEFT JOIN evidence e ON e.space_id=dep.space_id AND e.id=dep.source_evidence_id
   WHERE dep.run_id=a.id AND (b.id IS NULL
     OR b.direct_document_id IS DISTINCT FROM dep.direct_document_id
     OR b.source_evidence_id IS DISTINCT FROM dep.source_evidence_id
     OR (dep.direct_document_id IS NOT NULL AND (d.body IS NULL OR d.redacted_at IS NOT NULL))
     OR (dep.source_evidence_id IS NOT NULL AND (e.quote IS NULL OR e.redacted_at IS NOT NULL
       OR NOT EXISTS (SELECT 1 FROM analysis_input_source s WHERE s.space_id=dep.space_id
         AND s.run_id=dep.run_id AND s.source_record_id=e.record_id AND s.source_version=e.record_version)))))
);
$$;

COMMENT ON COLUMN analysis_run.contract_version IS 'Pinned at acceptance; explicit retry uses current configured version. Existing runs remain v1.';
COMMENT ON TABLE analysis_input_basis IS 'Exact v2 basis links including original direct input documents; no copied source content.';
COMMENT ON TABLE ai_proposal IS 'Unconfirmed proposals only. v2 payload lives in its redactable output document; no automatic CareItem mutation.';
