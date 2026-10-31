package com.hanmaum.backend.careitem.dto;

import java.util.List;

public record CareItemListResponse(
    List<CareItemSummary> items,
    long totalCount,
    List<CareItemSummary> reconfirmationItems,
    @io.swagger.v3.oas.annotations.media.Schema(
            types = {"integer", "null"},
            description = "예정 일정 탭에서만 제공하는 지난 일정 전체 건수")
        Long pastScheduleCount) {}
