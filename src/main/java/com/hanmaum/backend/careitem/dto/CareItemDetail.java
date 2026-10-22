package com.hanmaum.backend.careitem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record CareItemDetail(
    UUID itemId,
    UUID spaceId,
    String itemType,
    String version,
    @Schema(types = {"string", "null"}) String title,
    @Schema(types = {"string", "null"}) String description,
    @Schema(types = {"string", "null"}) String status,
    JsonNode fields,
    String visibility,
    boolean needsReconfirmation,
    boolean needsConflictReview,
    List<String> redactedFields,
    @Schema(nullable = true, description = "현재 유효한 편집 잠금. 토큰은 포함하지 않음") EditLockView editLock,
    @Schema(description = "답변 제출은 후속 구현. 현재 빈 배열") List<Object> answers,
    Instant createdAt,
    Instant updatedAt,
    @Schema(nullable = true, description = "소유자가 확정한 질문 답변. 원본 삭제 시 null")
        String confirmedAnswer) {}
