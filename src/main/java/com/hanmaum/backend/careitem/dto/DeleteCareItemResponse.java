package com.hanmaum.backend.careitem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record DeleteCareItemResponse(
    UUID itemId,
    String version,
    @Schema(allowableValues = "DELETED") String visibility,
    Instant deletedAt) {}
