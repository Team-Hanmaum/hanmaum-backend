CREATE TABLE app_user (
    id UUID NOT NULL,
    display_name TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT app_user_pk PRIMARY KEY (id)
);

CREATE TABLE social_account (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    provider TEXT NOT NULL,
    provider_user_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT social_account_pk PRIMARY KEY (id),
    CONSTRAINT social_account_provider_user_id_uq UNIQUE (provider, provider_user_id),
    CONSTRAINT social_account_provider_ck CHECK (provider IN ('GOOGLE', 'KAKAO')),
    CONSTRAINT social_account_user_fk FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE CASCADE
);

CREATE INDEX social_account_user_id_ix ON social_account (user_id);

COMMENT ON COLUMN app_user.display_name IS '표시 이름. 필수 여부와 길이 정책 확정 전 null 허용';
COMMENT ON COLUMN app_user.updated_at IS '마지막 프로필 수정 시각. 로그인 시각과 구분';
COMMENT ON COLUMN social_account.provider_user_id IS '제공자 내 사용자 식별자. 이메일로 계정을 자동 병합하지 않음';
