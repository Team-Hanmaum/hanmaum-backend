package com.hanmaum.backend.record.dto;

import com.hanmaum.backend.analysis.dto.AnalysisSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record SourceRecordSummary(
    UUID recordId,
    String content,
    SourceRecordAuthor author,
    Instant recordedAt,
    @Schema(description = "현재 로그인 회원이 원본 작성자인지 여부. 재참여 후에도 동일 회원 기준") boolean isMine,
    @Schema(description = "최신 분석 실행. 과거 미접수 소식은 null", nullable = true)
        AnalysisSummary latestAnalysis) {}
