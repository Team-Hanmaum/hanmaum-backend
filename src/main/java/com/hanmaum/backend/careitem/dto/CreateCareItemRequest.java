package com.hanmaum.backend.careitem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = CareItemRequestDeserializer.class)
public record CreateCareItemRequest(
    String itemType,
    String title,
    @Schema(types = {"string", "null"}) String description,
    ScheduleFields fields,
    UUID clientRequestId) {
  @Override
  public String toString() {
    return "CreateCareItemRequest[id=" + clientRequestId + "]";
  }
}
