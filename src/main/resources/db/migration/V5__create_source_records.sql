CREATE TABLE source_record (
    id UUID NOT NULL PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES care_space (id),
    author_membership_id UUID,
    body TEXT,
    recorded_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version >= 1),
    lifecycle TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (lifecycle IN ('ACTIVE', 'ERASED')),
    erased_at TIMESTAMPTZ,
    create_command_id UUID UNIQUE REFERENCES command_receipt (id) ON DELETE SET NULL,
    CONSTRAINT source_record_space_id_uq UNIQUE (space_id, id),
    CONSTRAINT source_record_author_fk FOREIGN KEY (space_id, author_membership_id)
        REFERENCES space_membership (space_id, id),
    CONSTRAINT source_record_active_ck CHECK (
        lifecycle <> 'ACTIVE' OR (body IS NOT NULL AND char_length(body) BETWEEN 1 AND 10000
            AND recorded_at IS NOT NULL AND author_membership_id IS NOT NULL AND erased_at IS NULL)
    ),
    CONSTRAINT source_record_erased_ck CHECK (
        lifecycle <> 'ERASED' OR (body IS NULL AND erased_at IS NOT NULL)
    )
);

CREATE INDEX source_record_active_page_ix
    ON source_record (space_id, recorded_at DESC, id DESC) WHERE lifecycle = 'ACTIVE';
CREATE INDEX source_record_author_ix ON source_record (author_membership_id);

COMMENT ON COLUMN source_record.body IS
    '공백·줄바꿈을 보존한 원문. 공개 입력은 최대 10000 UTF-16 코드 단위로 애플리케이션에서 검증. 삭제 시 NULL';
COMMENT ON COLUMN source_record.recorded_at IS
    '서버 UTC 등록 시각. 향후 분석의 상대 날짜 기준으로 고정하며 재시도 시 변경하지 않음';
COMMENT ON COLUMN source_record.author_membership_id IS
    '작성 당시 같은 공간의 참여. 참여 종료 후에도 기록을 유지하며 현재 참여 여부와 별개';
COMMENT ON COLUMN source_record.version IS
    '초기값 1. 향후 삭제·늦은 분석 결과의 재노출 방지를 위한 원본 버전';
COMMENT ON TABLE source_record IS
    'AI 실행과 독립적인 원본. 삭제 상태는 후속 삭제 구현의 기반이며 삭제 API·파생 내용 제거는 별도 구현';
