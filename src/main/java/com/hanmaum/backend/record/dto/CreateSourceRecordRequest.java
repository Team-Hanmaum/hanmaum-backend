package com.hanmaum.backend.record.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateSourceRecordRequest(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            maxLength = 10000,
            description = "공백만 입력 불가. 최대 10,000 UTF-16 코드 단위. 공백·줄바꿈 원문 보존",
            example = "오늘 병원에서 다음 달 15일에 다시 오라고 했어요.")
        @NotNull(message = "소식 내용을 입력해주세요.")
        @JsonDeserialize(using = SourceContentDeserializer.class)
        String content,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "FE가 생성한 UUID. 새 작성에는 새 ID, 같은 작성 재전송에는 동일 ID·본문 사용")
        @NotNull(message = "요청 ID를 입력해주세요.")
        UUID clientRequestId) {}
