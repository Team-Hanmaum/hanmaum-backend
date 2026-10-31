package com.hanmaum.backend.dashboard.service;

import com.hanmaum.backend.careitem.service.CareItemQueryService;
import com.hanmaum.backend.dashboard.dto.DashboardResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DashboardService {
  private final CareItemQueryService items;
  private final Clock clock;

  public DashboardService(CareItemQueryService items, Clock clock) {
    this.items = items;
    this.clock = clock;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public DashboardResponse get(UUID actor, UUID space) {
    Instant asOf = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var highlights = items.highlights(actor, space, asOf);
    return new DashboardResponse(
        space,
        asOf,
        highlights.upcomingSchedules(),
        highlights.pastScheduleCount(),
        highlights.remainingTasks(),
        highlights.unconfirmedQuestions(),
        highlights.recentChanges());
  }
}
