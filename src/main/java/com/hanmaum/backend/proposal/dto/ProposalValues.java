package com.hanmaum.backend.proposal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

/** Read-only projection, not a CareItem write/validation contract. */
@Schema(
    description = "itemType에 해당하는 조회 전용 값. 미상·변환 불가 값은 null",
    oneOf = {
      ProposalValues.Schedule.class,
      ProposalValues.Task.class,
      ProposalValues.Question.class,
      ProposalValues.Observation.class,
      ProposalValues.Guidance.class,
      ProposalValues.V2Schedule.class,
      ProposalValues.V2Task.class,
      ProposalValues.V2Question.class
    })
public sealed interface ProposalValues {
  @Schema(
      name = "V2ScheduleProposalValues",
      requiredProperties = {
        "title",
        "description",
        "status",
        "scheduledDate",
        "scheduledTime",
        "location"
      },
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  record V2Schedule(
      @Schema(nullable = true) String title,
      @Schema(nullable = true) String description,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) LocalDate scheduledDate,
      @Schema(nullable = true) String scheduledTime,
      @Schema(nullable = true) String location)
      implements ProposalValues {}

  @Schema(
      name = "V2TaskProposalValues",
      requiredProperties = {"title", "description", "status", "dueDate", "assigneeMembershipId"},
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  record V2Task(
      @Schema(nullable = true) String title,
      @Schema(nullable = true) String description,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) LocalDate dueDate,
      @Schema(nullable = true) UUID assigneeMembershipId)
      implements ProposalValues {}

  @Schema(
      name = "V2QuestionProposalValues",
      requiredProperties = {"title", "description", "status", "answerText"},
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  record V2Question(
      @Schema(nullable = true) String title,
      @Schema(nullable = true) String description,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String answerText)
      implements ProposalValues {}

  String title();

  String description();

  @Schema(
      name = "ScheduleProposalValues",
      requiredProperties = {"title", "description", "scheduledDate", "scheduledTime", "location"},
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  record Schedule(
      String title,
      @Schema(nullable = true) String description,
      @Schema(nullable = true) LocalDate scheduledDate,
      @Schema(
              nullable = true,
              pattern = "^([01][0-9]|2[0-3]):[0-5][0-9]$",
              description = "HH:mm, Asia/Seoul. 미상은 null이며 00:00으로 채우지 않음")
          String scheduledTime,
      @Schema(nullable = true) String location)
      implements ProposalValues {}

  @Schema(
      name = "TaskProposalValues",
      requiredProperties = {"title", "description", "dueDate", "assigneeMembershipId"},
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  record Task(
      String title,
      @Schema(nullable = true) String description,
      @Schema(nullable = true) LocalDate dueDate,
      @Schema(nullable = true, description = "동일 공간의 현재 참여 ID만 반환. 미상·유효하지 않으면 null")
          UUID assigneeMembershipId)
      implements ProposalValues {}

  @Schema(
      name = "QuestionProposalValues",
      requiredProperties = {"title", "description"},
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  record Question(String title, @Schema(nullable = true) String description)
      implements ProposalValues {}

  @Schema(
      name = "ObservationProposalValues",
      requiredProperties = {"title", "description", "observedDate", "observedTimeText"},
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  record Observation(
      @Schema(nullable = true) String title,
      @Schema(nullable = true) String description,
      @Schema(nullable = true) LocalDate observedDate,
      @Schema(nullable = true) String observedTimeText)
      implements ProposalValues {}

  @Schema(
      name = "GuidanceProposalValues",
      requiredProperties = {"title", "description", "sourceName", "receivedDate"},
      additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
  record Guidance(
      @Schema(nullable = true) String title,
      @Schema(nullable = true) String description,
      @Schema(nullable = true) String sourceName,
      @Schema(nullable = true) LocalDate receivedDate)
      implements ProposalValues {}
}
