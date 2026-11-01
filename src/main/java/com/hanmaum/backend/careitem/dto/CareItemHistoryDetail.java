package com.hanmaum.backend.careitem.dto;

import com.hanmaum.backend.record.dto.SourceRecordAuthor;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record CareItemHistoryDetail(
    UUID itemId,
    String version,
    @Schema(nullable = true) String previousVersion,
    @Schema(nullable = true) JsonNode before,
    @Schema(nullable = true) JsonNode after,
    SourceRecordAuthor changedBy,
    Instant changedAt,
    @Schema(description = "저장된 결정/문서 연결로 해당 변경과의 관계가 확인되는 근거 ID만 반환") List<UUID> evidenceIds,
    List<String> redactedFields) {}
