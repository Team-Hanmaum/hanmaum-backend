ALTER TABLE change_request_entry DROP CONSTRAINT change_request_source_ck;
ALTER TABLE change_request_entry ADD CONSTRAINT change_request_source_ck CHECK (
    (source_type='AI_PROPOSAL' AND proposal_id IS NOT NULL)
    OR (source_type='DIRECT_INPUT' AND proposal_id IS NULL
        AND action IN ('CREATE_ITEM','UPDATE_ITEM','RESOLVE_QUESTION'))
);

CREATE INDEX change_request_question_answers_ix ON change_request_entry(space_id,target_item_id)
    WHERE source_type='DIRECT_INPUT' AND action='RESOLVE_QUESTION';
