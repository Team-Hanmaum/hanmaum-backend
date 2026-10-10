package com.hanmaum.backend.carespace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record CreateCareSpaceResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID spaceId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "생성자의 참여 ID")
        UUID membershipId) {}
