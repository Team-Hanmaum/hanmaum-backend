package com.hanmaum.backend.careitem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CareItemEvidenceResponse(List<Evidence> items) {
  public record Evidence(
      UUID evidenceId,
      @Schema(description = "RECORD_QUOTE, DIRECT_INPUT 또는 DERIVED") String kind,
      @Schema(description = "VALID 또는 REDACTED") String status,
      @Schema(nullable = true) UUID recordId,
      @Schema(nullable = true, description = "인용문 또는 직접 입력의 JSON 표현. 제거된 내용은 null") String text,
      @Schema(nullable = true) UUID createdByMembershipId,
      @Schema(nullable = true, description = "소식 원본의 기록 시각이 제거된 경우 null") Instant createdAt,
      @Schema(description = "현재 필드별 근거 범위를 저장하지 않아 빈 배열. 전체 필드의 근거라는 뜻이 아님")
          List<String> fieldPaths) {}
}
