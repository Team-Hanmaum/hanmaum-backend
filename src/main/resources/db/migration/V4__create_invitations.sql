ALTER TABLE space_membership ADD CONSTRAINT space_membership_space_id_uq UNIQUE (space_id, id);

CREATE TABLE invitation (
    id UUID NOT NULL PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES care_space (id),
    issued_by_membership_id UUID NOT NULL,
    token_hash TEXT NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    encrypted_url TEXT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT invitation_space_id_uq UNIQUE (space_id, id),
    CONSTRAINT invitation_issuer_fk FOREIGN KEY (space_id, issued_by_membership_id)
        REFERENCES space_membership (space_id, id),
    CONSTRAINT invitation_expiry_ck CHECK (expires_at = issued_at + INTERVAL '24 hours')
);

CREATE INDEX invitation_issuer_ix ON invitation (issued_by_membership_id);
ALTER TABLE space_membership ADD COLUMN invitation_id UUID;
ALTER TABLE space_membership ADD CONSTRAINT space_membership_invitation_fk
    FOREIGN KEY (space_id, invitation_id) REFERENCES invitation (space_id, id);
CREATE INDEX space_membership_invitation_ix ON space_membership (invitation_id)
    WHERE invitation_id IS NOT NULL;
CREATE INDEX space_membership_ended_ix ON space_membership (space_id, user_id, ended_at DESC)
    WHERE ended_at IS NOT NULL;

COMMENT ON COLUMN invitation.encrypted_url IS
    '동일 발급 요청의 링크 재전달용 AES-GCM 암호문. 키는 DB 외부에 보관하며 원본 URL·토큰은 평문 저장하지 않음';
COMMENT ON COLUMN invitation.issued_by_membership_id IS
    '발급 당시 참여. 이 참여가 종료되면 링크 무효이며 새 참여로 복원하지 않음';
COMMENT ON COLUMN space_membership.invitation_id IS
    '해당 참여에 사용한 같은 공간의 초대. 공간 생성자·V3 기존 참여는 NULL';
