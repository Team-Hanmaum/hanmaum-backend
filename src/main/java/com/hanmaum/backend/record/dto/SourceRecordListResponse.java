package com.hanmaum.backend.record.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record SourceRecordListResponse(
    List<SourceRecordSummary> items,
    @Schema(description = "다음 조회에 그대로 전달하는 커서. 마지막 페이지면 null", nullable = true) String nextCursor,
    boolean hasNext) {}
