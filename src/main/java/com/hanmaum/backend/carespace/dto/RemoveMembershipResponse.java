package com.hanmaum.backend.carespace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "최초 성공 시점의 내보내기 결과. 현재 공간 버전은 공간 상세 재조회")
public record RemoveMembershipResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID membershipId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant endedAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "4") String spaceVersion) {}
