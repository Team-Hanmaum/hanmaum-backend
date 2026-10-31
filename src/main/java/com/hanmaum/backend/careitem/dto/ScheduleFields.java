package com.hanmaum.backend.careitem.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ScheduleFields(
    @Schema(
            types = {"string", "null"},
            example = "2026-10-15",
            description = "날짜 미상은 null")
        String scheduledDate,
    @Schema(
            types = {"string", "null"},
            example = "10:00",
            description = "Asia/Seoul HH:mm, 시각 미상은 null")
        String scheduledTime,
    @Schema(types = {"string", "null"}) String location)
    implements CreateCareItemRequest.Fields {}
