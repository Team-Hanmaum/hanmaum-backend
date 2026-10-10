CREATE TABLE care_space (
    id UUID NOT NULL PRIMARY KEY,
    owner_membership_id UUID NOT NULL,
    owner_user_id UUID NOT NULL REFERENCES app_user (id),
    owner_active BOOLEAN NOT NULL DEFAULT TRUE CHECK (owner_active),
    subject_label TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version >= 1),
    lifecycle TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (lifecycle IN ('ACTIVE', 'DELETING')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT care_space_label_ck CHECK (
        char_length(subject_label) BETWEEN 1 AND 30
        AND subject_label !~ ('[' || chr(10) || chr(13) || chr(11) || chr(12)
            || chr(133) || chr(8232) || chr(8233) || ']')
        AND subject_label = btrim(subject_label,
            chr(9) || chr(10) || chr(11) || chr(12) || chr(13) || chr(28) || chr(29)
            || chr(30) || chr(31) || chr(32) || chr(160) || chr(5760) || chr(8192)
            || chr(8193) || chr(8194) || chr(8195) || chr(8196) || chr(8197)
            || chr(8198) || chr(8199) || chr(8200) || chr(8201) || chr(8202)
            || chr(8232) || chr(8233) || chr(8239) || chr(8287) || chr(12288))
    ),
    CONSTRAINT care_space_owner_label_uq UNIQUE (owner_user_id, subject_label)
);

CREATE TABLE space_membership (
    id UUID NOT NULL PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES care_space (id),
    user_id UUID REFERENCES app_user (id),
    joined_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    end_reason TEXT,
    is_active BOOLEAN GENERATED ALWAYS AS (ended_at IS NULL AND user_id IS NOT NULL) STORED,
    CONSTRAINT space_membership_user_ck CHECK (user_id IS NOT NULL OR ended_at IS NOT NULL),
    CONSTRAINT space_membership_owner_ref_uq UNIQUE (space_id, id, user_id, is_active)
);

CREATE UNIQUE INDEX space_membership_active_user_uq
    ON space_membership (space_id, user_id) WHERE is_active;
CREATE INDEX space_membership_user_joined_ix
    ON space_membership (user_id, joined_at, space_id) WHERE is_active;

-- Deferred only until commit: a space and its first membership must be created together.
-- The composite FK also prevents a different-space, different-user or ended owner.
ALTER TABLE care_space ADD CONSTRAINT care_space_active_owner_fk
    FOREIGN KEY (id, owner_membership_id, owner_user_id, owner_active)
    REFERENCES space_membership (space_id, id, user_id, is_active)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE command_receipt (
    id UUID NOT NULL PRIMARY KEY,
    actor_user_id UUID NOT NULL REFERENCES app_user (id),
    client_request_id UUID NOT NULL,
    command_kind TEXT NOT NULL,
    scope_key TEXT NOT NULL,
    request_digest TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status = 'SUCCEEDED'),
    result_refs JSONB NOT NULL CHECK (jsonb_typeof(result_refs) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT command_receipt_actor_request_uq UNIQUE (actor_user_id, client_request_id)
);

COMMENT ON COLUMN care_space.owner_user_id IS
    '현재 소유자별 호칭 유일성 검사. 복합 FK로 소유자 참여의 사용자와 일치시킴';
COMMENT ON COLUMN care_space.owner_active IS
    '소유자가 현재 참여자인지 복합 FK로 검증하기 위한 DB 내부 상수';
COMMENT ON COLUMN care_space.subject_label IS
    '앞뒤 공백 제거 후 필수, 한 줄, Unicode code point 기준 최대 30자';
COMMENT ON COLUMN care_space.version IS
    '초기값 1. 공간 정보·소유권·참여 변경 시 업무 트랜잭션당 한 번 증가';
COMMENT ON COLUMN command_receipt.request_digest IS
    '서버 비밀키를 사용한 HMAC-SHA256. 원문 및 전체 응답 저장 금지';
COMMENT ON TABLE command_receipt IS
    '현재 동기 생성의 성공 결과만 저장. 처리 중 상태는 트랜잭션 잠금으로 판정. TTL 미설정';
