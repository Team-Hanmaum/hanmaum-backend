-- Apply with proposal/change-request writes stopped, as for other transactional index builds.
CREATE INDEX proposal_decision_item_version_ix
    ON proposal_decision (space_id, item_id, item_version) INCLUDE (proposal_id)
    WHERE item_id IS NOT NULL;

CREATE INDEX change_request_applied_item_version_ix
    ON change_request_entry (space_id, result_item_id, result_item_version) INCLUDE (proposal_id)
    WHERE status = 'APPLIED' AND proposal_id IS NOT NULL;
