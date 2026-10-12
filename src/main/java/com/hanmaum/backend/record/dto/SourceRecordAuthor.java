package com.hanmaum.backend.record.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record SourceRecordAuthor(
    @Schema(description = "작성 당시 참여 ID. 회원 연결이 제거된 경우 null", nullable = true) UUID membershipId,
    @Schema(description = "표시 이름. 미설정 또는 회원 연결 제거 시 null", nullable = true) String displayName,
    @Schema(description = "작성자 회원 연결 제거 여부. 공간 참여 종료만으로 true가 되지 않음") boolean withdrawn) {}
