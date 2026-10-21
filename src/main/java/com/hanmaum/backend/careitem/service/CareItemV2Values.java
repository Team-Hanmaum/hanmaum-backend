package com.hanmaum.backend.careitem.service;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Storage projection with an assignee resolved by the caller to an active same-space membership.
 * Strict types/dates/status are subsequently checked by the v2 contract.
 */
public final class CareItemV2Values {
  private CareItemV2Values() {}

  public static ObjectNode project(
      ObjectMapper mapper, String type, String status, JsonNode body, UUID activeAssignee) {
    ObjectNode out = mapper.createObjectNode();
    out.set("title", body.path("title").isMissingNode() ? mapper.nullNode() : body.get("title"));
    out.set(
        "description",
        body.path("description").isMissingNode() ? mapper.nullNode() : body.get("description"));
    List<String> fields =
        switch (type) {
          case "SCHEDULE" -> List.of("scheduledDate", "scheduledTime", "location");
          case "TASK" -> List.of("dueDate");
          case "QUESTION" -> List.of();
          case "OBSERVATION" -> List.of("observedDate", "observedTimeText");
          case "GUIDANCE" -> List.of("sourceName", "receivedDate");
          default -> throw new IllegalArgumentException("Unsupported candidate type");
        };
    for (String field : fields)
      out.set(
          field,
          body.path("fields").has(field) ? body.path("fields").get(field) : mapper.nullNode());
    if (type.equals("TASK"))
      out.put("assigneeMembershipId", activeAssignee == null ? null : activeAssignee.toString());
    if (List.of("SCHEDULE", "TASK", "QUESTION").contains(type)) out.put("status", status);
    // Confirmed-answer storage is a separate feature; never infer it from unconfirmed AI output.
    if (type.equals("QUESTION")) out.putNull("answerText");
    return out;
  }
}
