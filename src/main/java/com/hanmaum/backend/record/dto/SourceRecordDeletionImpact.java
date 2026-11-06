package com.hanmaum.backend.record.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record SourceRecordDeletionImpact(
    UUID recordId,
    @Schema(example = "1") String recordVersion,
    Counts counts,
    List<AffectedItem> affectedItems,
    @Schema(description = "현재 요청자·원본·영향 범위에 묶인 확인값") String impactToken) {
  public record Counts(long proposals, long evidence, long items, long historyEntries) {}

  public record AffectedItem(
      UUID itemId,
      @Schema(example = "2") String itemVersion,
      @Schema(description = "원본 삭제의 영향을 받지 않는 유효 근거가 하나 이상 남는지 여부") boolean hasIndependentEvidence,
      @Schema(description = "현재 저장된 값의 출처를 기준으로 판단한 삭제 후 재확인 필요 여부")
          boolean requiresReconfirmation) {}
}
