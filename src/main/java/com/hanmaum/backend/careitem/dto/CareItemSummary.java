package com.hanmaum.backend.careitem.dto;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record CareItemSummary(
    UUID itemId,
    String itemType,
    String version,
    String title,
    String status,
    boolean needsReconfirmation,
    boolean needsConflictReview,
    JsonNode fields,
    String assigneeDisplayName) {}
