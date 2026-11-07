package com.hanmaum.backend.carespace.dto;

import java.util.UUID;

public record CareSpaceDeletionImpact(
    UUID spaceId, String spaceVersion, Counts counts, String impactToken) {
  public record Counts(
      long members, long records, long items, long proposals, long requests, long historyEntries) {}

  @Override
  public String toString() {
    return "CareSpaceDeletionImpact[space=" + spaceId + "]";
  }
}
