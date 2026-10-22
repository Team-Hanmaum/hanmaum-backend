ALTER TABLE content_document DROP CONSTRAINT content_document_kind_check;
ALTER TABLE content_document ADD CONSTRAINT content_document_kind_check
    CHECK (kind IN ('AI_OUTPUT', 'DIRECT_INPUT', 'CONFIRMED_ITEM', 'CHANGE_REQUEST'));

CREATE TABLE change_request (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES care_space(id),
    submitted_by_membership_id UUID NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    UNIQUE (space_id,id),
    FOREIGN KEY (space_id,submitted_by_membership_id) REFERENCES space_membership(space_id,id)
);
CREATE INDEX change_request_page_ix ON change_request(space_id,submitted_at DESC,id DESC);

CREATE TABLE change_request_decision_batch (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES care_space(id),
    decided_by_membership_id UUID NOT NULL,
    decision TEXT NOT NULL CHECK (decision IN ('APPLIED','DECLINED','CANCELED')),
    decided_at TIMESTAMPTZ NOT NULL,
    UNIQUE (space_id,id),
    FOREIGN KEY (space_id,decided_by_membership_id) REFERENCES space_membership(space_id,id)
);

CREATE TABLE change_request_entry (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL,
    request_id UUID NOT NULL,
    ordinal_no INTEGER NOT NULL CHECK (ordinal_no >= 0),
    proposal_id UUID NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('CREATE_ITEM','UPDATE_ITEM','RESOLVE_QUESTION','LINK_EVIDENCE')),
    target_item_id UUID,
    expected_item_version BIGINT CHECK (expected_item_version >= 1),
    request_document_id UUID NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPLIED','DECLINED','CANCELED')),
    decision_batch_id UUID,
    decision_ordinal_no INTEGER,
    result_item_id UUID,
    result_item_version BIGINT CHECK (result_item_version >= 1),
    result_document_id UUID,
    UNIQUE (space_id,id),
    UNIQUE (request_id,ordinal_no),
    UNIQUE (request_id,proposal_id),
    UNIQUE (decision_batch_id,decision_ordinal_no),
    FOREIGN KEY (space_id,request_id) REFERENCES change_request(space_id,id),
    FOREIGN KEY (space_id,proposal_id) REFERENCES ai_proposal(space_id,id),
    FOREIGN KEY (space_id,target_item_id) REFERENCES care_item(space_id,id),
    FOREIGN KEY (space_id,request_document_id) REFERENCES content_document(space_id,id),
    FOREIGN KEY (space_id,decision_batch_id) REFERENCES change_request_decision_batch(space_id,id),
    FOREIGN KEY (space_id,result_item_id) REFERENCES care_item(space_id,id),
    FOREIGN KEY (space_id,result_document_id) REFERENCES content_document(space_id,id),
    CHECK ((action='CREATE_ITEM' AND target_item_id IS NULL AND expected_item_version IS NULL)
        OR (action<>'CREATE_ITEM' AND target_item_id IS NOT NULL AND expected_item_version IS NOT NULL)),
    CHECK ((status='PENDING' AND decision_batch_id IS NULL AND decision_ordinal_no IS NULL)
        OR (status<>'PENDING' AND decision_batch_id IS NOT NULL AND decision_ordinal_no >= 0)),
    CHECK ((status='APPLIED' AND result_item_id IS NOT NULL AND result_item_version IS NOT NULL AND result_document_id IS NOT NULL)
        OR (status<>'APPLIED' AND result_item_id IS NULL AND result_item_version IS NULL AND result_document_id IS NULL))
);
CREATE INDEX change_request_entry_proposal_ix ON change_request_entry(space_id,proposal_id) WHERE status='PENDING';
CREATE INDEX change_request_submitter_ix ON change_request(space_id,submitted_by_membership_id);

-- INVALIDATED is a live view of an undecided entry, not a decision that can erase its audit trail.
-- All writes serialize on the existing care_space row lock before checking this function.
CREATE FUNCTION change_request_entry_invalid_reason(wanted_entry UUID) RETURNS TEXT LANGUAGE SQL STABLE AS $$
SELECT CASE
    WHEN e.status<>'PENDING' THEN NULL
    WHEN NOT m.is_active THEN 'REQUESTER_LEFT'
    WHEN e.target_item_id IS NOT NULL AND
      (i.id IS NULL OR i.visibility<>'VISIBLE' OR i.needs_reconfirmation OR NOT care_document_available(i.current_document_id))
      THEN 'TARGET_UNAVAILABLE'
    WHEN e.target_item_id IS NOT NULL AND i.version<>e.expected_item_version THEN 'TARGET_VERSION_CHANGED'
    WHEN p.resolution<>'OPEN' THEN 'PROPOSAL_DECIDED'
    WHEN p.invalidated_at IS NOT NULL OR a.invalidated_at IS NOT NULL OR a.status<>'COMPLETED'
      OR NOT analysis_inputs_valid(a.id) OR NOT care_document_available(p.output_document_id)
      OR basis.quote IS NULL OR basis.redacted_at IS NOT NULL THEN 'PROPOSAL_INVALIDATED'
    WHEN NOT care_document_available(e.request_document_id) THEN 'CONTENT_UNAVAILABLE'
    ELSE NULL END
FROM change_request_entry e
JOIN change_request r ON r.space_id=e.space_id AND r.id=e.request_id
JOIN space_membership m ON m.space_id=r.space_id AND m.id=r.submitted_by_membership_id
JOIN ai_proposal p ON p.space_id=e.space_id AND p.id=e.proposal_id
JOIN analysis_run a ON a.space_id=p.space_id AND a.id=p.run_id
JOIN evidence basis ON basis.space_id=p.space_id AND basis.id=p.primary_evidence_id
LEFT JOIN care_item i ON i.space_id=e.space_id AND i.id=e.target_item_id
WHERE e.id=wanted_entry;
$$;

COMMENT ON TABLE change_request IS 'Immutable member submission identity. Decisions are per entry and use the current owner.';
COMMENT ON TABLE change_request_entry IS 'Requested text lives only in redactable content_document with AI provenance. No bearer tokens or copies in receipt metadata.';
