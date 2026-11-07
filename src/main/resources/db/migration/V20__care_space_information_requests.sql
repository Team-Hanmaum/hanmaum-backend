ALTER TABLE change_request_entry
    ADD COLUMN expected_space_version BIGINT CHECK (expected_space_version >= 1),
    ADD COLUMN result_space_version BIGINT CHECK (result_space_version >= 1);

ALTER TABLE change_request_entry DROP CONSTRAINT change_request_entry_action_check;
ALTER TABLE change_request_entry ADD CONSTRAINT change_request_entry_action_check
    CHECK (action IN ('CREATE_ITEM','UPDATE_ITEM','RESOLVE_QUESTION','LINK_EVIDENCE','DELETE_ITEM','UPDATE_SPACE'));
ALTER TABLE change_request_entry DROP CONSTRAINT change_request_source_ck;
ALTER TABLE change_request_entry ADD CONSTRAINT change_request_source_ck CHECK (
    (source_type='AI_PROPOSAL' AND proposal_id IS NOT NULL AND action NOT IN ('DELETE_ITEM','UPDATE_SPACE'))
    OR (source_type='DIRECT_INPUT' AND proposal_id IS NULL
        AND action IN ('CREATE_ITEM','UPDATE_ITEM','RESOLVE_QUESTION','DELETE_ITEM','UPDATE_SPACE'))
);

ALTER TABLE change_request_entry DROP CONSTRAINT change_request_entry_check;
ALTER TABLE change_request_entry ADD CONSTRAINT change_request_entry_target_ck CHECK (
    (action='UPDATE_SPACE' AND expected_space_version IS NOT NULL AND target_item_id IS NULL AND expected_item_version IS NULL)
    OR (action='CREATE_ITEM' AND expected_space_version IS NULL AND target_item_id IS NULL AND expected_item_version IS NULL)
    OR (action NOT IN ('CREATE_ITEM','UPDATE_SPACE') AND expected_space_version IS NULL AND target_item_id IS NOT NULL AND expected_item_version IS NOT NULL)
);
ALTER TABLE change_request_entry DROP CONSTRAINT change_request_entry_check2;
ALTER TABLE change_request_entry ADD CONSTRAINT change_request_entry_result_ck CHECK (
    (status='APPLIED' AND result_document_id IS NOT NULL AND (
        (action='UPDATE_SPACE' AND result_space_version IS NOT NULL AND result_item_id IS NULL AND result_item_version IS NULL)
        OR (action<>'UPDATE_SPACE' AND result_space_version IS NULL AND result_item_id IS NOT NULL AND result_item_version IS NOT NULL)))
    OR (status<>'APPLIED' AND result_item_id IS NULL AND result_item_version IS NULL AND result_document_id IS NULL AND result_space_version IS NULL)
);
CREATE INDEX change_request_pending_space_ix ON change_request_entry(space_id,expected_space_version)
    WHERE action='UPDATE_SPACE' AND status='PENDING';

CREATE OR REPLACE FUNCTION change_request_entry_invalid_reason(wanted_entry UUID) RETURNS TEXT LANGUAGE SQL STABLE AS $$
SELECT CASE
    WHEN e.status<>'PENDING' THEN NULL
    WHEN NOT m.is_active THEN 'REQUESTER_LEFT'
    WHEN e.action='UPDATE_SPACE' AND s.lifecycle<>'ACTIVE' THEN 'SPACE_UNAVAILABLE'
    WHEN e.action='UPDATE_SPACE' AND s.version<>e.expected_space_version THEN 'SPACE_VERSION_CHANGED'
    WHEN e.target_item_id IS NOT NULL AND
      (i.id IS NULL OR i.visibility<>'VISIBLE'
       OR (e.action<>'DELETE_ITEM' AND (i.needs_reconfirmation OR NOT care_document_available(i.current_document_id))))
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
JOIN care_space s ON s.id=e.space_id
JOIN space_membership m ON m.space_id=r.space_id AND m.id=r.submitted_by_membership_id
LEFT JOIN ai_proposal p ON p.space_id=e.space_id AND p.id=e.proposal_id
LEFT JOIN analysis_run a ON a.space_id=p.space_id AND a.id=p.run_id
LEFT JOIN evidence basis ON basis.space_id=p.space_id AND basis.id=p.primary_evidence_id
LEFT JOIN care_item i ON i.space_id=e.space_id AND i.id=e.target_item_id
WHERE e.id=wanted_entry;
$$;
