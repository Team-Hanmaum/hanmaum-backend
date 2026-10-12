package com.hanmaum.backend.record.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record CreateSourceRecordResponse(
    UUID recordId,
    @Schema(type = "string", example = "1") String version,
    Instant recordedAt,
    @Schema(
            types = {"null"},
            accessMode = Schema.AccessMode.READ_ONLY,
            description = "현재 분석 실행 미연결. 원본 저장 성공과 분석 성공은 별개")
        Void analysis) {}
