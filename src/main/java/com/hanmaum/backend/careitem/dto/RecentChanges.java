package com.hanmaum.backend.careitem.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RecentChanges(List<Change> items, long totalCount) {
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
