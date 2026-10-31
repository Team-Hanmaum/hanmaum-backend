-- Direct causes only. Point reads walk ancestors; bulk history reads walk descendants
-- of the same unavailable documents. All parent references are constrained to one space.
CREATE VIEW care_document_local_visibility AS
SELECT d.space_id, d.id,
       d.body IS NOT NULL AND d.redacted_at IS NULL AND
           (p.id IS NULL OR (p.invalidated_at IS NULL AND analysis_provenance_valid(p.run_id))) AS available
FROM content_document d
LEFT JOIN ai_proposal p ON p.output_document_id = d.id;

CREATE OR REPLACE FUNCTION care_document_available(wanted_document UUID)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
WITH RECURSIVE ancestors(id) AS (
    SELECT wanted_document
    UNION
    SELECT p.parent_document_id FROM care_item_document_parent p JOIN ancestors a ON a.id = p.document_id
)
SELECT NOT EXISTS (
    SELECT 1 FROM ancestors a LEFT JOIN care_document_local_visibility d ON d.id = a.id
    WHERE d.available IS NOT TRUE
);
$$;

CREATE INDEX care_item_history_space_recent_ix
    ON care_item_history (space_id, changed_at DESC, item_id ASC, version DESC);
