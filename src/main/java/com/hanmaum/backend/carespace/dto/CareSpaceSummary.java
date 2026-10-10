package com.hanmaum.backend.carespace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record CareSpaceSummary(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID spaceId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "엄마") String subjectLabel,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "요청자의 현재 참여 ID")
        UUID membershipId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CareSpaceRole role,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "소유자를 포함한 현재 참여자 수")
        long memberCount) {}
