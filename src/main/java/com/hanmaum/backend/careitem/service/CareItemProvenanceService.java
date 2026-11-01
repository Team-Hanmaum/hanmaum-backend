package com.hanmaum.backend.careitem.service;

import com.hanmaum.backend.careitem.dto.*;
import com.hanmaum.backend.careitem.repository.CareItemProvenanceRepository;
import com.hanmaum.backend.careitem.repository.CareItemProvenanceRepository.History;
import com.hanmaum.backend.carespace.service.CareSpaceQueryService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CareItemProvenanceService {
  private static final Map<String, List<String>> FIELDS =
      Map.of(
          "SCHEDULE",
          List.of("scheduledDate", "scheduledTime", "location"),
          "TASK",
          List.of("dueDate", "assigneeMembershipId"),
          "QUESTION",
          List.of(),
          "OBSERVATION",
          List.of("observedDate", "observedTimeText"),
          "GUIDANCE",
          List.of("sourceName", "receivedDate"));
  private final CareSpaceQueryService spaces;
  private final CareItemProvenanceRepository repository;
  private final ObjectMapper mapper;

  public CareItemProvenanceService(
      CareSpaceQueryService spaces, CareItemProvenanceRepository repository, ObjectMapper mapper) {
    this.spaces = spaces;
    this.repository = repository;
    this.mapper = mapper;
  }

  public CareItemEvidenceResponse evidence(UUID actor, UUID space, UUID item) {
    requireItem(actor, space, item);
    return new CareItemEvidenceResponse(repository.evidence(space, item));
  }

  public CareItemHistoryResponse history(UUID actor, UUID space, UUID item) {
    String type = requireItem(actor, space, item);
    return new CareItemHistoryResponse(
        repository.history(space, item, null).stream()
            .map(
                row ->
                    new CareItemHistoryResponse.Entry(
                        Long.toString(row.version()),
                        changeKind(row, type),
                        row.author(),
                        row.changedAt(),
                        redactions(row)))
            .toList());
  }

  public CareItemHistoryDetail detail(UUID actor, UUID space, UUID item, String version) {
    long parsed = CareMutationInput.version(mapper.valueToTree(version), "version", true);
    String type = requireItem(actor, space, item);
    var row =
        repository.history(space, item, parsed).stream()
            .findFirst()
            .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    return new CareItemHistoryDetail(
        item,
        Long.toString(row.version()),
        row.previousVersion() == null ? null : row.previousVersion().toString(),
        values(row.previousBody(), row.previousStatus(), type),
        values(row.body(), row.status(), type),
        row.author(),
        row.changedAt(),
        repository.historyEvidence(space, item, parsed),
        redactions(row));
  }

  private String requireItem(UUID actor, UUID space, UUID item) {
    spaces.detail(actor, space);
    return repository
        .itemType(space, item)
        .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
  }

  private List<String> redactions(History row) {
    List<String> result = new ArrayList<>();
    if (row.previousVersion() != null && row.previousBody() == null) result.add("before");
    if (row.body() == null) result.add("after");
    return result;
  }

  private JsonNode values(String body, String status, String type) {
    if (body == null) return null;
    JsonNode document = mapper.readTree(body);
    ObjectNode result = mapper.createObjectNode();
    for (String key : List.of("title", "description"))
      result.set(key, document.path(key).isMissingNode() ? mapper.nullNode() : document.get(key));
    ObjectNode fields = result.putObject("fields");
    for (String key : FIELDS.get(type)) {
      JsonNode value = document.path("fields").path(key);
      fields.set(key, value.isMissingNode() ? mapper.nullNode() : value);
    }
    result.put("status", status);
    if (type.equals("QUESTION"))
      result.set(
          "confirmedAnswer",
          document.path("confirmedAnswer").isMissingNode()
              ? mapper.nullNode()
              : document.get("confirmedAnswer"));
    return result;
  }

  private String changeKind(History row, String type) {
    if (row.operation().equals("UNASSIGN")) return "ASSIGNEE_CHANGE";
    if (!row.operation().equals("UPDATE") || row.body() == null || row.previousBody() == null)
      return row.operation();
    JsonNode after = values(row.body(), row.status(), type),
        before = values(row.previousBody(), row.previousStatus(), type);
    ObjectNode a = (ObjectNode) after.deepCopy(), b = (ObjectNode) before.deepCopy();
    a.remove("status");
    b.remove("status");
    if (!Objects.equals(row.status(), row.previousStatus()) && a.equals(b)) return "STATUS_CHANGE";
    if (type.equals("TASK") && Objects.equals(row.status(), row.previousStatus())) {
      JsonNode oldAssignee = b.at("/fields/assigneeMembershipId"),
          newAssignee = a.at("/fields/assigneeMembershipId");
      ((ObjectNode) a.get("fields")).remove("assigneeMembershipId");
      ((ObjectNode) b.get("fields")).remove("assigneeMembershipId");
      if (!oldAssignee.equals(newAssignee) && a.equals(b)) return "ASSIGNEE_CHANGE";
    }
    return "UPDATE";
  }
}
