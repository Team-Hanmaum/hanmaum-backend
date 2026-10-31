package com.hanmaum.backend.careitem.dto;

import com.hanmaum.backend.careitem.service.CareItemInput;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** Strict JSON types; numbers/booleans are never coerced to care text. */
public class CareItemRequestDeserializer extends ValueDeserializer<CreateCareItemRequest> {
  @Override
  public CreateCareItemRequest deserialize(JsonParser parser, DeserializationContext context) {
    JsonNode body = parser.readValueAsTree();
    object(body, "", Set.of("itemType", "title", "description", "fields", "clientRequestId"));
    JsonNode fields = body.path("fields");
    String type = text(body.path("itemType"), "itemType");
    CreateCareItemRequest.Fields parsed;
    if ("SCHEDULE".equals(type)) {
      object(fields, "fields", Set.of("scheduledDate", "scheduledTime", "location"));
      parsed =
          new ScheduleFields(
              text(fields.path("scheduledDate"), "fields.scheduledDate"),
              text(fields.path("scheduledTime"), "fields.scheduledTime"),
              text(fields.path("location"), "fields.location"));
    } else if ("TASK".equals(type)) {
      object(fields, "fields", Set.of("dueDate", "assigneeMembershipId"));
      parsed =
          new CreateCareItemRequest.TaskFields(
              text(fields.path("dueDate"), "fields.dueDate"),
              text(fields.path("assigneeMembershipId"), "fields.assigneeMembershipId"));
    } else if ("QUESTION".equals(type)) {
      object(fields, "fields", Set.of());
      parsed = new CreateCareItemRequest.QuestionFields();
    } else throw CareItemInput.invalid("itemType", "일정·할 일·질문만 직접 등록할 수 있습니다.");
    String id = text(body.path("clientRequestId"), "clientRequestId");
    UUID key;
    try {
      key = id == null ? null : UUID.fromString(id);
      if (key != null && !key.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
    } catch (IllegalArgumentException exception) {
      throw CareItemInput.invalid("clientRequestId", "UUID 요청 ID를 입력해주세요.");
    }
    return new CreateCareItemRequest(
        text(body.path("itemType"), "itemType"),
        text(body.path("title"), "title"),
        text(body.path("description"), "description"),
        parsed,
        key);
  }

  private static String text(JsonNode value, String field) {
    if (value.isNull() || value.isMissingNode()) return null;
    if (!value.isString()) throw CareItemInput.invalid(field, "문자열 또는 null을 입력해주세요.");
    return value.asString();
  }

  private static void object(JsonNode value, String field, Set<String> allowed) {
    if (!value.isObject())
      throw CareItemInput.invalid(field.isEmpty() ? "body" : field, "객체를 입력해주세요.");
    for (String name : value.propertyNames()) {
      if (!allowed.contains(name))
        throw CareItemInput.invalid(
            field.isEmpty() ? name : field + "." + name, "이번 API에서 지원하지 않는 필드입니다.");
    }
  }
}
