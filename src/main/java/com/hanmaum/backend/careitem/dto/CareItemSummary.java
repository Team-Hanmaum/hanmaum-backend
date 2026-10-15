package com.hanmaum.backend.careitem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record CareItemSummary(
    UUID itemId,
    String itemType,
    String version,
    @Schema(types = {"string", "null"}) String title,
    @Schema(types = {"string", "null"}) String status,
    boolean needsReconfirmation,
    boolean needsConflictReview,
    JsonNode fields,
    @Schema(types = {"string", "null"}) String assigneeDisplayName) {}
