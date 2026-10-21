package com.hanmaum.backend.proposal.service;

import com.hanmaum.backend.proposal.dto.ProposalItemType;
import com.hanmaum.backend.proposal.dto.ProposalValues;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** An explicit allowlist prevents internal changes/unknown fields becoming a public schema. */
@Component
public class ProposalValueMapper {
  private final ObjectMapper mapper;

  public ProposalValueMapper(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public ProposalValues map(ProposalItemType type, String document, UUID currentAssignee) {
    JsonNode root = mapper.readTree(document);
    String title = text(root, "title");
    JsonNode changes = root.path("changes");
    String description = value(changes, "description", "content");
    return switch (type) {
      case SCHEDULE ->
          new ProposalValues.Schedule(
              title,
              description,
              date(value(changes, "scheduledDate", "date")),
              time(value(changes, "scheduledTime", "time")),
              text(changes, "location"));
      case TASK ->
          new ProposalValues.Task(
              title, description, date(value(changes, "dueDate", "date")), currentAssignee);
      case QUESTION -> new ProposalValues.Question(title, description);
      case OBSERVATION ->
          new ProposalValues.Observation(
              title,
              description,
              date(value(changes, "observedDate", "date")),
              text(changes, "observedTimeText"));
      case GUIDANCE ->
          new ProposalValues.Guidance(
              title,
              description,
              text(changes, "sourceName"),
              date(value(changes, "receivedDate", "date")));
    };
  }

  public ProposalValues v2(ProposalItemType type, JsonNode fields, UUID assignee) {
    String title = rawText(fields, "title"),
        description = rawText(fields, "description"),
        status = rawText(fields, "status");
    return switch (type) {
      case SCHEDULE ->
          new ProposalValues.V2Schedule(
              title,
              description,
              status,
              date(rawText(fields, "scheduledDate")),
              time(rawText(fields, "scheduledTime")),
              rawText(fields, "location"));
      case TASK ->
          new ProposalValues.V2Task(
              title, description, status, date(rawText(fields, "dueDate")), assignee);
      case QUESTION ->
          new ProposalValues.V2Question(title, description, status, rawText(fields, "answerText"));
      case OBSERVATION ->
          new ProposalValues.Observation(
              title,
              description,
              date(rawText(fields, "observedDate")),
              rawText(fields, "observedTimeText"));
      case GUIDANCE ->
          new ProposalValues.Guidance(
              title,
              description,
              rawText(fields, "sourceName"),
              date(rawText(fields, "receivedDate")));
    };
  }

  private String rawText(JsonNode node, String key) {
    return node.path(key).isString() ? node.path(key).asText() : null;
  }

  private String text(JsonNode node, String key) {
    var value = node.path(key);
    return value.isString() && !value.asText().isBlank() ? value.asText() : null;
  }

  private String value(JsonNode node, String canonical, String alias) {
    String preferred = text(node, canonical);
    String legacy = text(node, alias);
    // Conflicting representations must not silently become one asserted value.
    if (preferred != null && legacy != null && !preferred.equals(legacy)) return null;
    return preferred != null ? preferred : legacy;
  }

  private LocalDate date(String value) {
    if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return null;
    try {
      return LocalDate.parse(value);
    } catch (DateTimeException exception) {
      return null;
    }
  }

  private String time(String value) {
    return value != null && value.matches("([01][0-9]|2[0-3]):[0-5][0-9]") ? value : null;
  }
}
