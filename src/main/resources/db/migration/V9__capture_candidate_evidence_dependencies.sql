-- Keep the IDs captured at claim time even if a basis link or evidence row is later removed.
-- Do not backfill from today's basis: it cannot prove what an older worker actually consumed.
ALTER TABLE analysis_input_item ADD COLUMN source_evidence_ids UUID[];

COMMENT ON COLUMN analysis_input_item.source_evidence_ids IS
    'Claim-time evidence IDs without copied quotes. Empty array means no source evidence; NULL means a legacy, uncaptured dependency and must fail completion validation.';
