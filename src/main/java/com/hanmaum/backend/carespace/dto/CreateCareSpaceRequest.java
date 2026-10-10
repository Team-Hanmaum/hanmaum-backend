package com.hanmaum.backend.carespace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateCareSpaceRequest(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "엄마",
            description = "앞뒤 공백 제거 후 필수, 한 줄, 최대 30 Unicode code point. 내부 공백 유지")
        @NotNull(message = "돌봄 대상 호칭을 입력해주세요.")
        @JsonDeserialize(using = SubjectLabelDeserializer.class)
        String subjectLabel,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "FE가 생성한 UUID. 같은 생성 시도의 재요청에서는 유지")
        @NotNull(message = "요청 ID를 입력해주세요.")
        UUID clientRequestId) {}
