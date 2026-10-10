package com.hanmaum.backend.carespace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record CareSpaceListResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CareSpaceSummary> items) {
  public CareSpaceListResponse {
    items = List.copyOf(items);
  }
}
