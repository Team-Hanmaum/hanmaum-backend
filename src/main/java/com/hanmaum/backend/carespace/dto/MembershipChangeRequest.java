package com.hanmaum.backend.carespace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import tools.jackson.databind.annotation.JsonDeserialize;

public record MembershipChangeRequest(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "3",
            pattern = "^[1-9][0-9]*$",
            description = "조회한 공간 버전. 1~9223372036854775807의 십진 정수 문자열")
        @JsonDeserialize(using = SpaceVersionDeserializer.class)
        @NotNull(message = "공간 버전을 입력해주세요.")
        String expectedSpaceVersion,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "FE가 생성한 UUID. 같은 시도의 재전송에는 동일 값 사용")
        @NotNull(message = "요청 ID를 입력해주세요.")
        UUID clientRequestId) {}
