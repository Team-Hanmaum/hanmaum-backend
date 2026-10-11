package com.hanmaum.backend.carespace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record SpaceMemberSummary(
    UUID membershipId,
    @Schema(
            types = {"string", "null"},
            description = "최초 가입 시 저장한 표시 이름. 없으면 null")
        String displayName,
    CareSpaceRole role,
    boolean isMe) {}
