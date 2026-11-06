package com.hanmaum.backend.record.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record DeleteSourceRecordRequest(
    @NotNull(message = "원본 버전을 입력해주세요.")
        @Schema(type = "string", example = "1", description = "삭제 영향 조회에서 확인한 원본 버전")
        JsonNode expectedRecordVersion,
    @NotNull(message = "삭제 영향 확인값을 입력해주세요.")
        @Schema(type = "string", description = "삭제 영향 조회에서 받은 불투명 확인값")
        JsonNode impactToken,
    @NotNull(message = "요청 ID를 입력해주세요.") @Schema(description = "동일 삭제 요청 재전송에 사용하는 UUID")
        UUID clientRequestId) {}
