package com.hanmaum.backend.record.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record SourceRecordSummary(
    UUID recordId,
    String content,
    SourceRecordAuthor author,
    Instant recordedAt,
    @Schema(description = "현재 로그인 회원이 원본 작성자인지 여부. 재참여 후에도 동일 회원 기준") boolean isMine,
    @Schema(
            types = {"null"},
            accessMode = Schema.AccessMode.READ_ONLY,
            description = "현재 분석 실행 미연결")
        Void latestAnalysis) {}
