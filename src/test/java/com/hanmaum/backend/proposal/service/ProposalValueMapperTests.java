package com.hanmaum.backend.proposal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanmaum.backend.proposal.dto.ProposalItemType;
import com.hanmaum.backend.proposal.dto.ProposalValues;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ProposalValueMapperTests {
  private final JsonMapper json = JsonMapper.builder().build();
  private final ProposalValueMapper mapper = new ProposalValueMapper(json);

  @Test
  void mapsFiveTypesToTheirPublicFieldsWithoutExposingUnknownChanges() {
    var schedule =
        (ProposalValues.Schedule)
            project(
                ProposalItemType.SCHEDULE,
                Map.of(
                    "scheduledDate",
                    "2026-10-20",
                    "scheduledTime",
                    "09:30",
                    "location",
                    "병원",
                    "description",
                    "설명",
                    "title",
                    "덮어쓰기 금지",
                    "internal",
                    "private"));
    assertThat(schedule)
        .isEqualTo(
            new ProposalValues.Schedule("원래 제목", "설명", LocalDate.of(2026, 10, 20), "09:30", "병원"));
    var task =
        (ProposalValues.Task) project(ProposalItemType.TASK, Map.of("dueDate", "2026-11-01"));
    assertThat(task.dueDate()).isEqualTo(LocalDate.of(2026, 11, 1));
    assertThat(task.assigneeMembershipId()).isNull();
    assertThat(
            project(
                ProposalItemType.QUESTION,
                Map.of("description", "물어볼 내용", "confirmedAnswer", "미확정")))
        .isEqualTo(new ProposalValues.Question("원래 제목", "물어볼 내용"));
    assertThat(
            project(
                ProposalItemType.OBSERVATION,
                Map.of("observedDate", "2026-10-01", "observedTimeText", "아침 무렵")))
        .isEqualTo(
            new ProposalValues.Observation("원래 제목", null, LocalDate.of(2026, 10, 1), "아침 무렵"));
    assertThat(
            project(
                ProposalItemType.GUIDANCE,
                Map.of("sourceName", "보건소", "receivedDate", "2026-10-02")))
        .isEqualTo(new ProposalValues.Guidance("원래 제목", null, "보건소", LocalDate.of(2026, 10, 2)));
    assertThat(json.writeValueAsString(schedule)).doesNotContain("private", "internal", "changes");
  }

  @Test
  void absentValuesRemainNullWithoutInventingDatesTimesOrAssignees() {
    assertThat(project(ProposalItemType.SCHEDULE, Map.of()))
        .isEqualTo(new ProposalValues.Schedule("원래 제목", null, null, null, null));
    assertThat(
            project(
                ProposalItemType.TASK,
                Map.of("assigneeMembershipId", UUID.randomUUID().toString())))
        .isEqualTo(new ProposalValues.Task("원래 제목", null, null, null));
    UUID current = UUID.randomUUID();
    var task =
        (ProposalValues.Task)
            mapper.map(ProposalItemType.TASK, "{\"title\":\"할 일\",\"changes\":{}}", current);
    assertThat(task.assigneeMembershipId()).isEqualTo(current);
  }

  @Test
  void supportsExistingAliasesButDoesNotChooseBetweenConflictingValues() {
    assertThat(
            project(
                ProposalItemType.SCHEDULE,
                Map.of("content", "설명", "date", "2028-02-29", "time", "13:00")))
        .isEqualTo(
            new ProposalValues.Schedule("원래 제목", "설명", LocalDate.of(2028, 2, 29), "13:00", null));
    var conflicting =
        (ProposalValues.Schedule)
            project(
                ProposalItemType.SCHEDULE,
                Map.of(
                    "content",
                    "A",
                    "description",
                    "B",
                    "date",
                    "2026-01-01",
                    "scheduledDate",
                    "2026-01-02",
                    "time",
                    "12:00",
                    "scheduledTime",
                    "13:00"));
    assertThat(conflicting.description()).isNull();
    assertThat(conflicting.scheduledDate()).isNull();
    assertThat(conflicting.scheduledTime()).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"내일", "2026-02-29", "2026-02-30", "2026-2-01", "2026-10-01T09:00:00", " "})
  void unknownOrInvalidDatesAreNotCoerced(String value) {
    assertThat(
            ((ProposalValues.Schedule)
                    project(ProposalItemType.SCHEDULE, Map.of("scheduledDate", value)))
                .scheduledDate())
        .isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"오전", "24:00", "09:60", "9:00", "09:00:00", " "})
  void unknownOrInvalidTimesAreNotCoerced(String value) {
    assertThat(
            ((ProposalValues.Schedule)
                    project(ProposalItemType.SCHEDULE, Map.of("scheduledTime", value)))
                .scheduledTime())
        .isNull();
  }

  private ProposalValues project(ProposalItemType type, Map<String, String> changes) {
    return mapper.map(
        type, json.writeValueAsString(Map.of("title", "원래 제목", "changes", changes)), null);
  }
}
