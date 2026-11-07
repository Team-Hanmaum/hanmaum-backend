ALTER TABLE care_item_basis ADD COLUMN included_in_analysis BOOLEAN NOT NULL DEFAULT TRUE;

-- V16 stored the selected evidence as immediate edges of the reconfirmed document.
-- Keep later attachments and recover the selection for items already reconfirmed on V16.
WITH latest AS (
    SELECT DISTINCT ON (space_id,item_id) space_id,item_id,document_id,changed_at
    FROM care_item_history
    WHERE operation='RECONFIRM'
    ORDER BY space_id,item_id,version DESC
)
UPDATE care_item_basis b SET included_in_analysis=FALSE
FROM latest h
WHERE b.space_id=h.space_id AND b.item_id=h.item_id AND b.created_at<=h.changed_at
  AND NOT EXISTS (
      SELECT 1 FROM care_item_document_parent p
      WHERE p.space_id=b.space_id AND p.document_id=h.document_id
        AND p.parent_document_id=b.direct_document_id
  )
  AND NOT EXISTS (
      SELECT 1 FROM care_item_document_evidence e
      WHERE e.space_id=b.space_id AND e.document_id=h.document_id
        AND e.evidence_id=b.source_evidence_id
  );

CREATE OR REPLACE VIEW care_item_available_basis AS
SELECT b.space_id,b.item_id,b.id,b.direct_document_id,b.source_evidence_id,e.record_id,e.record_version
FROM care_item_basis b
LEFT JOIN evidence e ON e.space_id=b.space_id AND e.id=b.source_evidence_id
LEFT JOIN source_record r ON r.space_id=e.space_id AND r.id=e.record_id
LEFT JOIN ai_proposal p ON p.space_id=e.space_id AND p.primary_evidence_id=e.id
WHERE b.included_in_analysis
  AND ((b.direct_document_id IS NOT NULL AND care_document_available(b.direct_document_id))
       OR (e.quote IS NOT NULL AND e.redacted_at IS NULL AND r.lifecycle='ACTIVE' AND r.body IS NOT NULL
           AND r.version=e.record_version AND (p.id IS NULL OR care_document_available(p.output_document_id))));
