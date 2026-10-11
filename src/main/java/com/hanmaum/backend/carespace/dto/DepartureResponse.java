package com.hanmaum.backend.carespace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "본인이 종료한 참여의 최소 결과. 재참여·공간 삭제 후에도 동일 성공 요청은 이 결과만 반환")
public record DepartureResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID membershipId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant endedAt) {}
