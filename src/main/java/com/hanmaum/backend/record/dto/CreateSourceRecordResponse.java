package com.hanmaum.backend.record.dto;

import com.hanmaum.backend.analysis.dto.AnalysisSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record CreateSourceRecordResponse(
    UUID recordId,
    @Schema(type = "string", example = "1") String version,
    Instant recordedAt,
    @Schema(description = "최초 분석 접수 정보. 과거 분석 미접수 소식의 저장 재전송이면 null", nullable = true)
        AnalysisSummary analysis) {}
