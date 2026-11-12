package com.hanmaum.backend.carespace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record CareSpaceDeletionImpact(
    UUID spaceId, String spaceVersion, Counts counts, String impactToken) {
  @Schema(name = "CareSpaceDeletionCounts")
  public record Counts(
      long members, long records, long items, long proposals, long requests, long historyEntries) {}

  @Override
  public String toString() {
    return "CareSpaceDeletionImpact[space=" + spaceId + "]";
  }
}
