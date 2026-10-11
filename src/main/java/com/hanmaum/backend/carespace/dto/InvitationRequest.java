package com.hanmaum.backend.carespace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record InvitationRequest(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "FE가 생성한 UUID. 같은 발급/참여 시도의 재전송에는 동일 값 사용")
        @NotNull(message = "요청 ID를 입력해주세요.")
        UUID clientRequestId) {}
