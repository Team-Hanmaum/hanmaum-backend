CREATE TABLE share_link (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES care_space(id),
    issued_by_membership_id UUID NOT NULL,
    token_hash TEXT NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    url_prefix TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (space_id,id),
    FOREIGN KEY (space_id,issued_by_membership_id) REFERENCES space_membership(space_id,id)
);

CREATE TABLE share_link_target (
    space_id UUID NOT NULL,
    share_link_id UUID NOT NULL,
    ordinal_no INTEGER NOT NULL CHECK (ordinal_no >= 0),
    item_id UUID NOT NULL,
    item_type TEXT NOT NULL CHECK (item_type IN ('SCHEDULE','TASK','QUESTION','OBSERVATION','GUIDANCE')),
    shared_item_version BIGINT NOT NULL CHECK (shared_item_version >= 1),
    includes_change BOOLEAN NOT NULL,
    PRIMARY KEY (share_link_id,item_id),
    UNIQUE (share_link_id,ordinal_no),
    FOREIGN KEY (space_id,share_link_id) REFERENCES share_link(space_id,id),
    FOREIGN KEY (space_id,item_id) REFERENCES care_item(space_id,id)
);
CREATE INDEX share_link_space_ix ON share_link(space_id);
CREATE INDEX share_link_target_item_ix ON share_link_target(space_id,item_id);

COMMENT ON TABLE share_link IS 'Locator only: current login/membership required. No automatic expiry or content snapshots.';
COMMENT ON COLUMN share_link.url_prefix IS 'Original service URL prefix without token, names or care content. Preserves issuance replay after URL configuration changes.';
COMMENT ON COLUMN share_link_target.shared_item_version IS 'Issuance metadata only. Access always reads the latest authorized item, never a historical content snapshot.';
