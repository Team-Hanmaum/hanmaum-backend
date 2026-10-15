package com.hanmaum.backend.proposal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record ProposalSummary(
    UUID proposalId,
    UUID recordId,
    UUID analysisId,
    ProposalItemType itemType,
    ProposalAction action,
    ProposalStatus status,
    @Schema(nullable = true, description = "무효화된 제안은 null") String title,
    @Schema(nullable = true, description = "CREATE에는 기존 대상 없음") UUID targetItemId,
    @Schema(nullable = true, description = "상충 판정 계약 미연결로 null. 충돌 없음을 의미하지 않음")
        Boolean needsConflictReview,
    Instant createdAt) {}
