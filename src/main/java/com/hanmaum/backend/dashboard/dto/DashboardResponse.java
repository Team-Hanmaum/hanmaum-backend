package com.hanmaum.backend.dashboard.dto;

import com.hanmaum.backend.careitem.dto.CareItemSection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DashboardResponse(
    UUID spaceId,
    Instant asOf,
    CareItemSection upcomingSchedules,
    long pastScheduleCount,
    CareItemSection remainingTasks,
    CareItemSection unconfirmedQuestions,
    RecentChanges recentChanges) {
  public record RecentChanges(List<Change> items, long totalCount) {}

  public record Change(
      UUID itemId,
      String itemType,
      String version,
      String changeKind,
      Instant changedAt,
      UUID changedByMembershipId,
      String changedByDisplayName,
      String title) {}
}
