package com.hanmaum.backend.proposal.dto;

import com.hanmaum.backend.record.dto.SourceRecordAuthor;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProposalDetail(
    UUID proposalId,
    UUID spaceId,
    UUID recordId,
    UUID analysisId,
    ProposalItemType itemType,
    ProposalAction action,
    ProposalStatus status,
    @Schema(nullable = true) String title,
    @Schema(nullable = true, description = "CREATE는 null") UUID targetItemId,
    @Schema(nullable = true, description = "CREATE는 null") String analysisTargetVersion,
    @Schema(nullable = true, description = "CREATE는 null") String currentItemVersion,
    @Schema(nullable = true, description = "CREATE는 null") ProposalValues currentValues,
    @Schema(nullable = true, description = "무효 제안은 전체 null") ProposalValues proposedValues,
    @Schema(description = "현재 CREATE 범위는 빈 배열") List<Candidate> candidates,
    @Schema(description = "현재 주 원본 근거. 무효 제안은 빈 배열") List<Evidence> evidence,
    @Schema(nullable = true, description = "관계 판정 계약 미연결로 null") String relationKind,
    @Schema(nullable = true, description = "상충 판정 계약 미연결로 null") Boolean needsConflictReview,
    @Schema(nullable = true, description = "반영 API 미구현으로 발급하지 않음") String reviewToken,
    List<String> redactedFields,
    Instant createdAt) {
  public record Candidate(UUID itemId, String version, String title) {}

  public record Evidence(
      UUID evidenceId,
      UUID recordId,
      String quote,
      boolean valid,
      SourceRecordAuthor author,
      Instant recordedAt) {}
}
