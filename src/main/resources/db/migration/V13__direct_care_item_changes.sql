ALTER TABLE change_request_entry
    ALTER COLUMN proposal_id DROP NOT NULL,
    ADD COLUMN source_type TEXT NOT NULL DEFAULT 'AI_PROPOSAL',
    ADD CONSTRAINT change_request_source_ck CHECK (
        (source_type='AI_PROPOSAL' AND proposal_id IS NOT NULL) OR
        (source_type='DIRECT_INPUT' AND proposal_id IS NULL AND action IN ('CREATE_ITEM','UPDATE_ITEM'))
    );
CREATE INDEX change_request_pending_direct_ix ON change_request_entry(space_id,target_item_id,expected_item_version)
    WHERE source_type='DIRECT_INPUT' AND status='PENDING';

ALTER TABLE care_item_history DROP CONSTRAINT care_item_history_operation_check;
ALTER TABLE care_item_history ADD CONSTRAINT care_item_history_operation_check
    CHECK (operation IN ('CREATE','UPDATE','RESOLVE','LINK_EVIDENCE','UNASSIGN'));

CREATE OR REPLACE FUNCTION change_request_entry_invalid_reason(wanted_entry UUID) RETURNS TEXT LANGUAGE SQL STABLE AS $$
SELECT CASE
    WHEN e.status<>'PENDING' THEN NULL
    WHEN NOT m.is_active THEN 'REQUESTER_LEFT'
    WHEN e.target_item_id IS NOT NULL AND
      (i.id IS NULL OR i.visibility<>'VISIBLE' OR i.needs_reconfirmation OR NOT care_document_available(i.current_document_id))
      THEN 'TARGET_UNAVAILABLE'
    WHEN e.target_item_id IS NOT NULL AND i.version<>e.expected_item_version THEN 'TARGET_VERSION_CHANGED'
    WHEN p.resolution<>'OPEN' THEN 'PROPOSAL_DECIDED'
    WHEN e.proposal_id IS NOT NULL AND (p.invalidated_at IS NOT NULL OR a.invalidated_at IS NOT NULL OR a.status<>'COMPLETED'
      OR NOT analysis_inputs_valid(a.id) OR NOT care_document_available(p.output_document_id)
      OR basis.quote IS NULL OR basis.redacted_at IS NOT NULL) THEN 'PROPOSAL_INVALIDATED'
    WHEN NOT care_document_available(e.request_document_id) THEN 'CONTENT_UNAVAILABLE'
    ELSE NULL END
FROM change_request_entry e
JOIN change_request r ON r.space_id=e.space_id AND r.id=e.request_id
JOIN space_membership m ON m.space_id=r.space_id AND m.id=r.submitted_by_membership_id
LEFT JOIN ai_proposal p ON p.space_id=e.space_id AND p.id=e.proposal_id
LEFT JOIN analysis_run a ON a.space_id=p.space_id AND a.id=p.run_id
LEFT JOIN evidence basis ON basis.space_id=p.space_id AND basis.id=p.primary_evidence_id
LEFT JOIN care_item i ON i.space_id=e.space_id AND i.id=e.target_item_id
WHERE e.id=wanted_entry;
$$;
