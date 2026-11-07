ALTER TABLE care_item_edit_lock ADD COLUMN purpose TEXT NOT NULL DEFAULT 'EDIT'
    CHECK (purpose IN ('EDIT','RECONFIRMATION'));

ALTER TABLE care_item_history DROP CONSTRAINT care_item_history_operation_check;
ALTER TABLE care_item_history ADD CONSTRAINT care_item_history_operation_check
    CHECK (operation IN ('CREATE','UPDATE','RESOLVE','LINK_EVIDENCE','UNASSIGN','RECONFIRM'));

ALTER TABLE content_document DROP CONSTRAINT content_document_kind_check;
ALTER TABLE content_document ADD CONSTRAINT content_document_kind_check
    CHECK (kind IN ('AI_OUTPUT','DIRECT_INPUT','CONFIRMED_ITEM','CHANGE_REQUEST','RECONFIRMATION'));

CREATE TABLE care_item_document_evidence (
    space_id UUID NOT NULL,
    document_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    PRIMARY KEY (document_id,evidence_id),
    FOREIGN KEY (space_id,document_id) REFERENCES content_document(space_id,id),
    FOREIGN KEY (space_id,evidence_id) REFERENCES evidence(space_id,id)
);
CREATE INDEX care_item_document_evidence_reverse_ix
    ON care_item_document_evidence(space_id,evidence_id);

CREATE OR REPLACE VIEW care_document_local_visibility AS
SELECT d.space_id, d.id,
       d.body IS NOT NULL AND d.redacted_at IS NULL
           AND (p.id IS NULL OR (p.invalidated_at IS NULL AND analysis_provenance_valid(p.run_id)))
           AND NOT EXISTS (
               SELECT 1 FROM care_item_document_evidence link
               JOIN evidence e ON e.space_id=link.space_id AND e.id=link.evidence_id
               JOIN source_record r ON r.space_id=e.space_id AND r.id=e.record_id
               WHERE link.space_id=d.space_id AND link.document_id=d.id
                 AND (e.quote IS NULL OR e.redacted_at IS NOT NULL OR r.lifecycle<>'ACTIVE'
                      OR r.body IS NULL OR r.version<>e.record_version)
           ) AS available
FROM content_document d
LEFT JOIN ai_proposal p ON p.output_document_id = d.id;

CREATE VIEW care_item_available_basis AS
SELECT b.space_id,b.item_id,b.id,b.direct_document_id,b.source_evidence_id,e.record_id,e.record_version
FROM care_item_basis b
LEFT JOIN evidence e ON e.space_id=b.space_id AND e.id=b.source_evidence_id
LEFT JOIN source_record r ON r.space_id=e.space_id AND r.id=e.record_id
LEFT JOIN ai_proposal p ON p.space_id=e.space_id AND p.primary_evidence_id=e.id
WHERE (b.direct_document_id IS NOT NULL AND care_document_available(b.direct_document_id))
   OR (e.quote IS NOT NULL AND e.redacted_at IS NULL AND r.lifecycle='ACTIVE' AND r.body IS NOT NULL
       AND r.version=e.record_version AND (p.id IS NULL OR care_document_available(p.output_document_id)));
