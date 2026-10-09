package com.hanmaum.backend.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hanmaum.backend.user.entity.SocialProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "로그인한 회원의 기본 정보")
public record MyProfileResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "한마음 사용자 ID") UUID userId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "표시 이름. 제공자에서 확보하지 못한 경우 null")
        String displayName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "연결된 소셜 로그인 방식")
        List<SocialProvider> providers) {}
