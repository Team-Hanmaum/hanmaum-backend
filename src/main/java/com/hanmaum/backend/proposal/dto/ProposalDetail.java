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
    @Schema(description = "미확정 대상 후보. 무효 제안은 빈 배열") List<Candidate> candidates,
    @Schema(description = "현재 주 원본 근거. 무효 제안은 빈 배열") List<Evidence> evidence,
    @Schema(nullable = true, description = "v2 관계 판정. v1·무효 제안은 null") String relationKind,
    @Schema(nullable = true, description = "v2 CONFLICT 여부. v1·무효 제안은 null")
        Boolean needsConflictReview,
    @Schema(nullable = true, description = "유효한 미처리 제안의 현재 검토 확인값. 사용자·참여·제안·입력에 연결")
        String reviewToken,
    List<String> redactedFields,
    Instant createdAt,
    @Schema(description = "제안이 설정하는 필드. proposedValues의 나머지 null은 변경을 의미하지 않음")
        List<String> changedFields,
    @Schema(description = "명시적으로 지우려는 필드. 실제 반영 전 검토 필요") List<String> clearFields,
    List<String> reviewReasons,
    List<ComparisonBasis> comparisonBasis,
    @Schema(nullable = true, description = "반영/미적용 결정. 내용 제거 후에도 최소 결정 메타데이터 유지")
        Decision decision) {
  public record Decision(
      UUID batchId,
      Instant decidedAt,
      UUID itemId,
      String itemVersion,
      tools.jackson.databind.JsonNode appliedValues) {}

  public record ComparisonBasis(
      UUID basisId, UUID itemId, String kind, UUID recordId, String text) {}

  public record Candidate(UUID itemId, String version, String title) {}

  public record Evidence(
      UUID evidenceId,
      UUID recordId,
      String quote,
      boolean valid,
      SourceRecordAuthor author,
      Instant recordedAt) {}
}
