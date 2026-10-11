package com.hanmaum.backend.carespace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "최초 성공 시점의 이전 결과. 재전송도 동일 결과이며 현재 역할·소유권은 공간 상세 재조회")
public record TransferOwnershipResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID spaceId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID ownerMembershipId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "최초 이전 직후 역할 MEMBER")
        CareSpaceRole myRole,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "4") String spaceVersion) {}
