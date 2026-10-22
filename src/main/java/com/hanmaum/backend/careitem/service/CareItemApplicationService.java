package com.hanmaum.backend.careitem.service;

import com.hanmaum.backend.careitem.repository.CareItemApplicationRepository;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.proposal.code.ProposalErrorCode;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CareItemApplicationService {
  private static final Map<String, Set<String>> FIELDS =
      Map.of(
          "SCHEDULE",
          Set.of("scheduledDate", "scheduledTime", "location"),
          "TASK",
          Set.of("dueDate", "assigneeMembershipId"),
          "QUESTION",
          Set.of(),
          "OBSERVATION",
          Set.of("observedDate", "observedTimeText"),
          "GUIDANCE",
          Set.of("sourceName", "receivedDate"));
  private final CareItemApplicationRepository items;
  private final ObjectMapper mapper;

  public CareItemApplicationService(CareItemApplicationRepository items, ObjectMapper mapper) {
    this.items = items;
    this.mapper = mapper;
  }

  public CareItemApplicationRepository.Item lock(UUID space, UUID item) {
    return items
        .lock(space, item)
        .orElseThrow(
            () ->
                new ApiException(
                    com.hanmaum.backend.global.code.CommonErrorCode.RESOURCE_NOT_FOUND));
  }

  /** No writes here: every entry must be prepared before the first entry is persisted. */
  public Prepared prepare(
      UUID space,
      String type,
      String operation,
      CareItemApplicationRepository.Item current,
      JsonNode values,
      String path) {
    boolean create = operation.equals("CREATE");
    if (!create && current.version() == Long.MAX_VALUE) throw stateConflict();
    if (!FIELDS.containsKey(type)) throw CareItemInput.invalid(path, "허용하지 않는 유형입니다.");
    ObjectNode body =
        create ? mapper.createObjectNode() : (ObjectNode) mapper.readTree(current.body());
    String status = create ? initialStatus(type) : current.status();
    if (operation.equals("LINK_EVIDENCE")) {
      if (values != null && !values.isNull())
        throw CareItemInput.invalid(path, "근거 연결에는 values를 전달하지 않습니다.");
      return new Prepared(
          current.id(), type, operation, current.version() + 1, current.document(), body, status);
    }
    if (operation.equals("RESOLVE")) {
      if (!type.equals("QUESTION") || !"UNCONFIRMED".equals(status)) throw stateConflict();
      CareMutationInput.object(values, path, Set.of("confirmedAnswer"));
      body.put(
          "confirmedAnswer",
          CareMutationInput.text(values.path("confirmedAnswer"), path + ".confirmedAnswer", true));
      return new Prepared(
          current.id(),
          type,
          operation,
          current.version() + 1,
          current.document(),
          body,
          "CONFIRMED");
    }
    if (!create && type.equals("QUESTION") && "CONFIRMED".equals(status)) throw stateConflict();
    CareMutationInput.object(
        values,
        path,
        create
            ? Set.of("itemType", "title", "description", "fields")
            : Set.of("title", "description", "fields", "status"));
    if (create) {
      if (!type.equals(CareMutationInput.text(values.path("itemType"), path + ".itemType", true)))
        throw CareItemInput.invalid(path + ".itemType", "제안의 유형을 변경할 수 없습니다.");
      body.put("title", CareMutationInput.text(values.path("title"), path + ".title", true));
      body.put(
          "description",
          CareMutationInput.text(values.path("description"), path + ".description", false));
      body.set("fields", mapper.createObjectNode());
    }
    ObjectNode fields =
        body.path("fields").isObject()
            ? (ObjectNode) body.get("fields")
            : mapper.createObjectNode();
    // Ended assignees cannot be propagated through a new current document.
    if (type.equals("TASK")
        && fields.path("assigneeMembershipId").isString()
        && !items.activeAssignee(
            space,
            CareMutationInput.uuid(
                fields.get("assigneeMembershipId"), path + ".fields.assigneeMembershipId", false)))
      fields.putNull("assigneeMembershipId");
    for (String field : FIELDS.get(type)) if (!fields.has(field)) fields.putNull(field);
    body.set("fields", fields);
    ObjectNode original = body.deepCopy();
    String previousStatus = status;
    if (values.has("title"))
      body.put("title", CareMutationInput.text(values.get("title"), path + ".title", true));
    if (values.has("description"))
      body.put(
          "description",
          CareMutationInput.text(values.get("description"), path + ".description", false));
    if (create || values.has("fields")) {
      JsonNode input = values.path("fields");
      CareMutationInput.object(input, path + ".fields", FIELDS.get(type));
      for (String field : input.propertyNames()) {
        String value = CareMutationInput.text(input.get(field), path + ".fields." + field, false);
        if (field.equals("assigneeMembershipId") && value != null) {
          UUID assignee =
              CareMutationInput.uuid(input.get(field), path + ".fields." + field, false);
          if (!items.activeAssignee(space, assignee))
            throw CareItemInput.invalid(path + ".fields." + field, "같은 공간의 현재 참여자를 선택해주세요.");
          value = assignee.toString();
        }
        validateDateTime(field, value, path + ".fields." + field);
        fields.put(field, value);
      }
    }
    for (String field : FIELDS.get(type)) if (!fields.has(field)) fields.putNull(field);
    body.set("fields", fields);
    if (!create && values.has("status")) {
      String next = CareMutationInput.text(values.get("status"), path + ".status", true);
      if (!Objects.equals(next, status)) {
        boolean allowed =
            switch (type) {
              case "SCHEDULE" ->
                  "SCHEDULED".equals(status) && Set.of("COMPLETED", "CANCELED").contains(next);
              case "TASK" ->
                  switch (status) {
                    case "NOT_STARTED" -> Set.of("IN_PROGRESS", "COMPLETED").contains(next);
                    case "IN_PROGRESS" -> next.equals("COMPLETED");
                    case "COMPLETED" -> next.equals("IN_PROGRESS");
                    default -> false;
                  };
              default -> false;
            };
        if (!allowed) throw stateConflict();
        status = next;
      } else if (!Set.of("SCHEDULE", "TASK").contains(type)) throw stateConflict();
    }
    CareMutationInput.text(body.path("title"), path + ".title", true);
    if (!create && original.equals(body) && Objects.equals(status, previousStatus))
      throw CareItemInput.invalid(path, "실제로 변경할 내용을 하나 이상 입력해주세요.");
    return new Prepared(
        create ? UUID.randomUUID() : current.id(),
        type,
        operation,
        create ? 1 : current.version() + 1,
        create ? null : current.document(),
        body,
        status);
  }

  public Applied apply(
      UUID space,
      UUID member,
      Prepared p,
      UUID proposalDocument,
      UUID evidence,
      UUID run,
      Instant now) {
    UUID document =
        p.operation().equals("LINK_EVIDENCE")
            ? p.previousDocument()
            : items.document(
                space,
                mapper.writeValueAsString(p.body()),
                p.previousDocument(),
                proposalDocument,
                run,
                now);
    if (p.operation().equals("CREATE"))
      items.create(space, p.item(), p.type(), p.status(), document, member, now);
    else items.update(space, p.item(), p.version() - 1, document, p.status(), now);
    items.basis(space, p.item(), evidence, member, now);
    items.history(space, p.item(), p.version(), p.operation(), document, p.status(), member, now);
    return new Applied(p.item(), p.version(), document);
  }

  private String initialStatus(String type) {
    return switch (type) {
      case "SCHEDULE" -> "SCHEDULED";
      case "TASK" -> "NOT_STARTED";
      case "QUESTION" -> "UNCONFIRMED";
      default -> null;
    };
  }

  private void validateDateTime(String field, String value, String path) {
    if (value == null) return;
    try {
      if (Set.of("scheduledDate", "dueDate", "observedDate", "receivedDate").contains(field)) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
        LocalDate.parse(value);
      } else if (field.equals("scheduledTime")) {
        if (!value.matches("[0-9]{2}:[0-9]{2}")) throw new IllegalArgumentException();
        LocalTime.parse(value);
      }
    } catch (IllegalArgumentException | DateTimeException e) {
      throw CareItemInput.invalid(path, "유효한 날짜 또는 시각을 입력해주세요.");
    }
  }

  private ApiException stateConflict() {
    return new ApiException(ProposalErrorCode.PROPOSAL_STATE_CONFLICT);
  }

  public record Prepared(
      UUID item,
      String type,
      String operation,
      long version,
      UUID previousDocument,
      ObjectNode body,
      String status) {
    @Override
    public String toString() {
      return "PreparedItem[id=" + item + "]";
    }
  }

  public record Applied(UUID item, long version, UUID document) {}
}
