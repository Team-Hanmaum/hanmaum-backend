package com.hanmaum.backend.record.dto;

import com.hanmaum.backend.analysis.dto.AnalysisSummary;
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
    @Schema(description = "최신 분석 실행. 과거 미접수 소식은 null", nullable = true)
        AnalysisSummary latestAnalysis,
    @Schema(description = "최신 완료 실행에 저장된 유효한 미확정 제안 ID") List<UUID> proposalIds,
    @Schema(
            description =
                "현재 참여자 중 소유자 또는 원본 작성자이면 true. 영향 조회·삭제 시 현재 권한을 다시 확인하며, 삭제 실행은 원본 버전·영향 확인값을 검증")
        boolean canDelete) {}
