package com.hanmaum.backend.ai.client.v2;

import static com.hanmaum.backend.ai.client.v2.AiV2Json.*;

import com.hanmaum.backend.ai.code.AiFailureCode;
import com.hanmaum.backend.ai.dto.CareItemType;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisRequest;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisRequest.Basis;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisRequest.BasisKind;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisRequest.Candidate;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisResponse;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisResponse.BasisReference;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisResponse.Operation;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisResponse.RelationKind;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisResponse.ReviewReason;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisResponse.Suggestion;
import com.hanmaum.backend.ai.dto.v2.AiV2CurrentValues;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Strict wire contract and request-relative validation, independent of DB/product policies. */
public final class AiV2Contract {
  private final AiV2PayloadLimits limits;

  public AiV2Contract(AiV2PayloadLimits limits) {
    this.limits = java.util.Objects.requireNonNull(limits);
  }

  private static final Map<CareItemType, Set<String>> CHANGE_FIELDS =
      Map.of(
          CareItemType.SCHEDULE,
              Set.of(
                  "title", "description", "scheduledDate", "scheduledTime", "location", "status"),
          CareItemType.TASK, Set.of("title", "description", "dueDate", "status"),
          CareItemType.QUESTION, Set.of("title", "description"),
          CareItemType.OBSERVATION,
              Set.of("title", "description", "observedDate", "observedTimeText"),
          CareItemType.GUIDANCE, Set.of("title", "description", "sourceName", "receivedDate"));
  private static final Map<CareItemType, Set<String>> STATUSES =
      Map.of(
          CareItemType.SCHEDULE, Set.of("SCHEDULED", "COMPLETED", "CANCELED"),
          CareItemType.TASK, Set.of("NOT_STARTED", "IN_PROGRESS", "COMPLETED"),
          CareItemType.QUESTION, Set.of("UNCONFIRMED", "CONFIRMED"));
  private static final Map<Operation, Set<RelationKind>> RELATIONS =
      Map.of(
          Operation.CREATE, Set.of(RelationKind.NEW),
          Operation.UPDATE,
              Set.of(RelationKind.RELATED, RelationKind.CONFLICT, RelationKind.UNDETERMINED),
          Operation.RESOLVE,
              Set.of(RelationKind.RELATED, RelationKind.CONFLICT, RelationKind.UNDETERMINED),
          Operation.LINK_EVIDENCE, Set.of(RelationKind.DUPLICATE, RelationKind.UNDETERMINED));

  public AiV2AnalysisRequest parseRequest(byte[] raw) {
    try {
      JsonNode root = read(raw, limits.requestBytes());
      AiV2SizeRules.request(root);
      keys(
          root,
          "requestId",
          "careSpaceId",
          "recordId",
          "content",
          "recordedAt",
          "timezone",
          "candidates");
      String recordedAt = timestamp(root.get("recordedAt"));
      String timezone = string(root.get("timezone"));
      require(timezone.equals("Asia/Seoul"));
      var candidates = list(root.get("candidates"), this::candidate);
      unique(candidates.stream().map(Candidate::itemId).toList());
      return new AiV2AnalysisRequest(
          uuid(root.get("requestId")),
          uuid(root.get("careSpaceId")),
          uuid(root.get("recordId")),
          nonBlank(root.get("content")),
          recordedAt,
          timezone,
          candidates);
    } catch (RuntimeException exception) {
      throw new AiV2Exception(AiFailureCode.AI_INTEGRATION_ERROR);
    }
  }

  /** Revalidates constructed DTOs too; does not trim source text or impose v1 limits. */
  public byte[] encodeRequest(AiV2AnalysisRequest request) {
    try {
      byte[] raw = write(request, limits.requestBytes());
      parseRequest(raw);
      return raw;
    } catch (RuntimeException exception) {
      throw new AiV2Exception(AiFailureCode.AI_INTEGRATION_ERROR);
    }
  }

  /** Storage boundary for typed results: retains the same bounded wire validation. */
  public void validateResponse(AiV2AnalysisRequest request, AiV2AnalysisResponse response) {
    byte[] raw;
    try {
      raw = write(response, limits.responseBytes());
    } catch (RuntimeException exception) {
      throw new AiV2Exception(AiFailureCode.AI_INVALID_RESPONSE);
    }
    parseResponse(request, raw);
  }

  public AiV2AnalysisResponse parseResponse(AiV2AnalysisRequest request, byte[] raw) {
    // An invalid caller request is an integration error, not an invalid AI response.
    encodeRequest(request);
    try {
      JsonNode root = read(raw, limits.responseBytes());
      AiV2SizeRules.response(root);
      keys(root, "requestId", "suggestions");
      UUID id = uuid(root.get("requestId"));
      require(id.equals(request.requestId()));
      var suggestions = list(root.get("suggestions"), this::suggestion);
      var candidates = new HashMap<UUID, Candidate>();
      request.candidates().forEach(c -> candidates.put(c.itemId(), c));
      for (Suggestion suggestion : suggestions) validateReferences(request, candidates, suggestion);
      return new AiV2AnalysisResponse(id, suggestions);
    } catch (RuntimeException exception) {
      throw new AiV2Exception(AiFailureCode.AI_INVALID_RESPONSE);
    }
  }

  private Candidate candidate(JsonNode node) {
    keys(node, "itemId", "version", "type", "currentValues", "basis");
    CareItemType type = enumeration(node.get("type"), CareItemType.class);
    var basis =
        list(
            node.get("basis"),
            b -> {
              keys(b, "basisId", "kind", "text");
              return new Basis(
                  uuid(b.get("basisId")),
                  enumeration(b.get("kind"), BasisKind.class),
                  nonBlank(b.get("text")));
            });
    unique(basis.stream().map(Basis::basisId).toList());
    return new Candidate(
        uuid(node.get("itemId")),
        integer(node.get("version")),
        type,
        currentValues(type, node.get("currentValues")),
        basis);
  }

  private AiV2CurrentValues currentValues(CareItemType type, JsonNode node) {
    Set<String> fields = new HashSet<>(CHANGE_FIELDS.get(type));
    if (type == CareItemType.TASK) fields.add("assigneeMembershipId");
    if (type == CareItemType.QUESTION) fields.addAll(Set.of("status", "answerText"));
    keys(node, fields);
    for (String field : fields) {
      JsonNode value = node.get(field);
      if (field.equals("status")) require(STATUSES.get(type).contains(string(value)));
      else if (field.equals("assigneeMembershipId")) nullable(value, AiV2Json::uuid);
      else nullable(value, v -> fieldValue(field, v));
    }
    return switch (type) {
      case SCHEDULE -> MAPPER.treeToValue(node, AiV2CurrentValues.Schedule.class);
      case TASK ->
          new AiV2CurrentValues.Task(
              nullable(node.get("title"), AiV2Json::string),
              nullable(node.get("description"), AiV2Json::string),
              string(node.get("status")),
              nullable(node.get("dueDate"), AiV2Json::string),
              nullable(node.get("assigneeMembershipId"), AiV2Json::uuid));
      case QUESTION -> MAPPER.treeToValue(node, AiV2CurrentValues.Question.class);
      case OBSERVATION -> MAPPER.treeToValue(node, AiV2CurrentValues.Observation.class);
      case GUIDANCE -> MAPPER.treeToValue(node, AiV2CurrentValues.Guidance.class);
    };
  }

  private Suggestion suggestion(JsonNode node) {
    keys(
        node,
        "operation",
        "type",
        "targetItemId",
        "expectedVersion",
        "candidateItemIds",
        "title",
        "evidence",
        "changes",
        "clearFields",
        "relationKind",
        "reviewReasons",
        "basisReferences");
    JsonNode changesNode = node.get("changes");
    require(changesNode.isObject());
    var changes = new HashMap<String, String>();
    changesNode
        .properties()
        .forEach(entry -> changes.put(entry.getKey(), nonBlank(entry.getValue())));
    var references =
        list(
            node.get("basisReferences"),
            b -> {
              keys(b, "itemId", "basisIds");
              var ids = unique(list(b.get("basisIds"), AiV2Json::uuid));
              require(!ids.isEmpty());
              return new BasisReference(uuid(b.get("itemId")), ids);
            });
    unique(references.stream().map(BasisReference::itemId).toList());
    var suggestion =
        new Suggestion(
            enumeration(node.get("operation"), Operation.class),
            enumeration(node.get("type"), CareItemType.class),
            nullable(node.get("targetItemId"), AiV2Json::uuid),
            nullable(node.get("expectedVersion"), AiV2Json::integer),
            unique(list(node.get("candidateItemIds"), AiV2Json::uuid)),
            nonBlank(node.get("title")),
            nonBlank(node.get("evidence")),
            changes,
            unique(list(node.get("clearFields"), AiV2Json::string)),
            enumeration(node.get("relationKind"), RelationKind.class),
            unique(list(node.get("reviewReasons"), n -> enumeration(n, ReviewReason.class))),
            references);
    validateCombination(suggestion);
    validateChanges(suggestion);
    return suggestion;
  }

  private void validateCombination(Suggestion s) {
    var reasons = Set.copyOf(s.reviewReasons());
    var targetReasons = new HashSet<>(reasons);
    targetReasons.retainAll(Set.of(ReviewReason.TARGET_AMBIGUOUS, ReviewReason.TARGET_NOT_FOUND));
    require(RELATIONS.get(s.operation()).contains(s.relationKind()));
    require(s.operation() != Operation.RESOLVE || s.type() == CareItemType.QUESTION);
    require(!reasons.contains(ReviewReason.ASSIGNEE_UNRESOLVED) || s.type() == CareItemType.TASK);
    require(s.relationKind() != RelationKind.UNDETERMINED || !reasons.isEmpty());
    require(
        s.relationKind() != RelationKind.CONFLICT
            || reasons.contains(ReviewReason.CONFLICT) && !s.basisReferences().isEmpty());
    if (s.operation() == Operation.CREATE) {
      require(
          s.targetItemId() == null
              && s.expectedVersion() == null
              && s.candidateItemIds().isEmpty()
              && targetReasons.isEmpty());
    } else if (s.targetItemId() != null) {
      require(
          s.expectedVersion() != null && s.candidateItemIds().isEmpty() && targetReasons.isEmpty());
    } else {
      var reason =
          s.candidateItemIds().isEmpty()
              ? ReviewReason.TARGET_NOT_FOUND
              : ReviewReason.TARGET_AMBIGUOUS;
      require(s.expectedVersion() == null && targetReasons.equals(Set.of(reason)));
    }
  }

  private void validateChanges(Suggestion s) {
    if (s.operation() == Operation.RESOLVE) {
      require(s.changes().keySet().equals(Set.of("answerText")) && s.clearFields().isEmpty());
      return;
    }
    if (s.operation() == Operation.LINK_EVIDENCE) {
      require(s.changes().isEmpty() && s.clearFields().isEmpty());
      return;
    }
    var allowed = CHANGE_FIELDS.get(s.type());
    require(allowed.containsAll(s.changes().keySet()) && allowed.containsAll(s.clearFields()));
    require(s.operation() == Operation.UPDATE || s.clearFields().isEmpty());
    require(s.clearFields().stream().noneMatch(s.changes()::containsKey));
    if (s.operation() == Operation.UPDATE && s.changes().isEmpty() && s.clearFields().isEmpty()) {
      require(
          s.reviewReasons().contains(ReviewReason.ASSIGNEE_UNRESOLVED)
              || s.reviewReasons().contains(ReviewReason.VALUE_UNCLEAR));
    }
    s.changes()
        .forEach(
            (field, value) -> {
              if (field.equals("status")) require(STATUSES.get(s.type()).contains(value));
              else fieldValue(field, MAPPER.valueToTree(value));
            });
  }

  private void validateReferences(
      AiV2AnalysisRequest request, Map<UUID, Candidate> candidates, Suggestion s) {
    require(request.content().contains(s.evidence()));
    if (s.targetItemId() != null) {
      Candidate target = candidates.get(s.targetItemId());
      require(
          target != null
              && target.type() == s.type()
              && target.version().equals(s.expectedVersion()));
    }
    for (UUID id : s.candidateItemIds()) {
      Candidate candidate = candidates.get(id);
      require(candidate != null && candidate.type() == s.type());
    }
    var referenced = new HashSet<UUID>();
    for (BasisReference ref : s.basisReferences()) {
      Candidate candidate = candidates.get(ref.itemId());
      require(candidate != null);
      var basis = candidate.basis().stream().map(Basis::basisId).toList();
      require(basis.containsAll(ref.basisIds()));
      referenced.add(ref.itemId());
    }
    require(referenced.containsAll(s.candidateItemIds()));
    require(s.targetItemId() == null || referenced.contains(s.targetItemId()));
  }

  private String fieldValue(String field, JsonNode node) {
    String value = string(node);
    switch (field) {
      case "scheduledDate", "dueDate", "observedDate", "receivedDate" -> date(value);
      case "scheduledTime" -> {
        require(value.matches("[0-9]{2}:[0-9]{2}"));
        LocalTime.parse(value);
      }
      default -> {
        /* Free text, including observedTimeText, must remain unchanged. */
      }
    }
    return value;
  }

  private void date(String value) {
    require(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && LocalDate.parse(value).getYear() > 0);
  }

  private String timestamp(JsonNode node) {
    String value = string(node);
    require(
        value.matches(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
                + "(?:\\.[0-9]{1,6})?(?:Z|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])"));
    date(value.substring(0, 10));
    LocalTime.parse(value.substring(11, 19));
    return value;
  }
}
