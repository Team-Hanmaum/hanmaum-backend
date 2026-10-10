package com.hanmaum.backend.carespace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record CareSpaceDetail(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID spaceId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String subjectLabel,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "요청자의 현재 참여 ID")
        UUID membershipId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CareSpaceRole role,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long memberCount,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1", pattern = "^[1-9][0-9]*$")
        String version,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt) {}
