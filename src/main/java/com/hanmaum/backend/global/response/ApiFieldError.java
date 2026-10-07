package com.hanmaum.backend.global.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record ApiFieldError(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "요청 필드 경로",
            example = "entries[0].expectedItemVersion")
        String field,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "제출 값이나 내부 정보를 포함하지 않는 오류 안내")
        String reason) {}
