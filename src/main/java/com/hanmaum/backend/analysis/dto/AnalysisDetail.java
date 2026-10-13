package com.hanmaum.backend.analysis.dto;

import com.hanmaum.backend.analysis.entity.AnalysisStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AnalysisDetail(
    UUID analysisId,
    UUID recordId,
    AnalysisStatus status,
    List<UUID> proposalIds,
    Failure failure,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    boolean canRetry) {
  public record Failure(String code, String message) {}
}
