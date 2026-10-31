package com.hanmaum.backend.dashboard.dto;

import com.hanmaum.backend.careitem.dto.CareItemSection;
import com.hanmaum.backend.careitem.dto.RecentChanges;
import java.time.Instant;
import java.util.UUID;

public record DashboardResponse(
    UUID spaceId,
    Instant asOf,
    CareItemSection upcomingSchedules,
    long pastScheduleCount,
    CareItemSection remainingTasks,
    CareItemSection unconfirmedQuestions,
    RecentChanges recentChanges) {}
