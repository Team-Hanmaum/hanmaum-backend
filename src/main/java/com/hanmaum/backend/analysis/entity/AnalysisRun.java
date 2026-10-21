package com.hanmaum.backend.analysis.entity;

import com.hanmaum.backend.analysis.code.AnalysisFailureCode;
import java.time.Instant;
import java.util.UUID;

public record AnalysisRun(
    UUID id,
    UUID spaceId,
    UUID recordId,
    long recordVersion,
    long attemptNo,
    UUID requestId,
    Instant inputRecordedAt,
    UUID membershipId,
    AnalysisStatus status,
    AnalysisFailureCode failureCode,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    UUID workerToken,
    Instant leaseUntil,
    String contractVersion) {}
