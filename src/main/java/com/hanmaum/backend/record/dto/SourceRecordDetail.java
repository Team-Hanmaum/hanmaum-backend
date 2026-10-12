package com.hanmaum.backend.record.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SourceRecordDetail(
    UUID recordId,
    UUID spaceId,
    String content,
    SourceRecordAuthor author,
    Instant recordedAt,
    @Schema(type = "string", example = "1") String version,
    boolean isMine,
    @Schema(
            types = {"null"},
            accessMode = Schema.AccessMode.READ_ONLY,
            description = "현재 분석 실행 미연결")
        Void latestAnalysis,
    @Schema(description = "현재 제안 기능 미연결로 빈 배열") List<UUID> proposalIds,
    @Schema(description = "현재 소유자 또는 본인 원본의 삭제 권한 안내. 삭제 API는 후속 구현") boolean canDelete) {}
