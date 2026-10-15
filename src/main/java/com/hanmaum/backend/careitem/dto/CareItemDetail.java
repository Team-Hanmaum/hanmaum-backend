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
    @Schema(
            types = {"null"},
            description = "편집 잠금은 후속 구현")
        Void editLock,
    @Schema(description = "답변 제출은 후속 구현. 현재 빈 배열") List<Object> answers,
    Instant createdAt,
    Instant updatedAt) {}
