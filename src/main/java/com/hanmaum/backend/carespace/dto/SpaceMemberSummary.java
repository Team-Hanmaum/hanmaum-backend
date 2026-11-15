package com.hanmaum.backend.carespace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record SpaceMemberSummary(
    UUID membershipId,
    @Schema(
            types = {"string", "null"},
            description = "최초 가입 시 저장한 표시 이름. 없으면 null")
        String displayName,
    @Schema(
            types = {"string", "null"},
            format = "uri",
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "구성원 프로필 사진 URL. 없으면 null")
        String profileImageUrl,
    CareSpaceRole role,
    boolean isMe) {}
