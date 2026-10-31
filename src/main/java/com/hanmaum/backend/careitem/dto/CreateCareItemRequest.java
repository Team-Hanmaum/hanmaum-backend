package com.hanmaum.backend.careitem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = CareItemRequestDeserializer.class)
public record CreateCareItemRequest(
    String itemType,
    String title,
    @Schema(types = {"string", "null"}) String description,
    @Schema(oneOf = {ScheduleFields.class, TaskFields.class, QuestionFields.class}) Fields fields,
    UUID clientRequestId) {
  public sealed interface Fields permits ScheduleFields, TaskFields, QuestionFields {}

  public record TaskFields(String dueDate, String assigneeMembershipId) implements Fields {}

  public record QuestionFields() implements Fields {}

  @Override
  public String toString() {
    return "CreateCareItemRequest[id=" + clientRequestId + "]";
  }
}
