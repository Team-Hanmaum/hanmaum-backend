package com.hanmaum.backend.proposal.service;

import com.hanmaum.backend.careitem.service.CareItemV2Values;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiFieldError;
import com.hanmaum.backend.proposal.dto.ProposalAction;
import com.hanmaum.backend.proposal.dto.ProposalDetail;
import com.hanmaum.backend.proposal.dto.ProposalListResponse;
import com.hanmaum.backend.proposal.dto.ProposalStatus;
import com.hanmaum.backend.proposal.dto.ProposalSummary;
import com.hanmaum.backend.proposal.dto.ProposalValues;
import com.hanmaum.backend.proposal.repository.ProposalQueryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ProposalQueryService {
  private final ProposalQueryRepository proposals;
  private final ProposalCursor cursors;
  private final ProposalValueMapper values;
  private final ObjectMapper mapper;
  private final ProposalReviewToken reviews;

  public ProposalQueryService(
      ProposalQueryRepository proposals,
      ProposalCursor cursors,
      ProposalValueMapper values,
      ObjectMapper mapper,
      ProposalReviewToken reviews) {
    this.proposals = proposals;
    this.cursors = cursors;
    this.values = values;
    this.mapper = mapper;
    this.reviews = reviews;
  }

  public ProposalListResponse list(
      UUID userId, UUID spaceId, String recordId, String status, String cursor, int size) {
    if (size < 1 || size > 50) throw invalid("size", "조회 개수는 1 이상 50 이하로 입력해주세요.");
    UUID record = recordId(recordId);
    ProposalStatus filter = status(status);
    var position = cursors.decode(spaceId, record, filter, cursor);
    var result =
        authorized(
            proposals.find(
                userId,
                spaceId,
                record,
                filter,
                position == null ? null : position.createdAt(),
                position == null ? null : position.proposalId(),
                null,
                size + 1));
    boolean hasNext = result.proposals().size() > size;
    var page = result.proposals().stream().limit(size).toList();
    String next =
        hasNext
            ? cursors.encode(
                spaceId, record, filter, page.getLast().createdAt(), page.getLast().id())
            : null;
    var items =
        page.stream()
            .map(
                p -> {
                  var projected = project(p);
                  return new ProposalSummary(
                      p.id(),
                      p.recordId(),
                      p.analysisId(),
                      p.itemType(),
                      action(p),
                      p.status(),
                      title(p, projected),
                      uuid(payload(p), "targetItemId"),
                      conflict(p),
                      p.createdAt());
                })
            .toList();
    return new ProposalListResponse(items, next, hasNext);
  }

  public ProposalDetail detail(UUID userId, UUID spaceId, UUID proposalId) {
    var result = authorized(proposals.find(userId, spaceId, null, null, null, null, proposalId, 1));
    if (result.proposals().isEmpty()) throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
    var p = result.proposals().getFirst();
    if (p.contractVersion().equals("v2")) return detailV2(userId, spaceId, p);
    var projected = project(p);
    boolean redacted = p.document() == null;
    return new ProposalDetail(
        p.id(),
        spaceId,
        p.recordId(),
        p.analysisId(),
        p.itemType(),
        ProposalAction.CREATE_ITEM,
        p.status(),
        projected == null ? null : projected.title(),
        null,
        null,
        null,
        null,
        projected,
        List.of(),
        redacted
            ? List.of()
            : List.of(
                new ProposalDetail.Evidence(
                    p.evidenceId(), p.recordId(), p.quote(), true, p.author(), p.recordedAt())),
        null,
        null,
        reviews.issue(userId, spaceId, p.id()),
        redacted ? List.of("title", "proposedValues", "evidence") : List.of(),
        p.createdAt(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        proposals.decision(spaceId, p.id(), mapper));
  }

  private ProposalValues project(ProposalQueryRepository.View p) {
    return p.document() == null
        ? null
        : p.contractVersion().equals("v2")
            ? values.v2(p.itemType(), payload(p).path("changes"), p.assigneeId())
            : values.map(p.itemType(), p.document(), p.assigneeId());
  }

  private ProposalDetail detailV2(UUID user, UUID space, ProposalQueryRepository.View p) {
    boolean redacted = p.document() == null;
    JsonNode body = payload(p);
    UUID target = uuid(body, "targetItemId");
    var items =
        redacted
            ? List.<ProposalQueryRepository.InputItem>of()
            : proposals.inputItems(space, p.analysisId());
    var current = items.stream().filter(i -> i.id().equals(target)).findFirst().orElse(null);
    var currentValues =
        current == null
            ? null
            : values.v2(
                p.itemType(),
                CareItemV2Values.project(
                    mapper,
                    p.itemType().name(),
                    current.status(),
                    mapper.readTree(current.document()),
                    current.assigneeId()),
                current.assigneeId());
    var candidateIds =
        strings(body.path("candidateItemIds")).stream()
            .map(UUID::fromString)
            .collect(Collectors.toSet());
    var candidates =
        items.stream()
            .filter(i -> candidateIds.contains(i.id()))
            .map(
                i ->
                    new ProposalDetail.Candidate(
                        i.id(),
                        Long.toString(i.version()),
                        mapper.readTree(i.document()).path("title").asText()))
            .toList();
    Set<UUID> referenced = new java.util.HashSet<>();
    body.path("basisReferences")
        .forEach(
            r -> r.path("basisIds").forEach(id -> referenced.add(UUID.fromString(id.asText()))));
    var basis =
        redacted
            ? List.<ProposalDetail.ComparisonBasis>of()
            : proposals.inputBasis(space, p.analysisId()).stream()
                .filter(b -> referenced.contains(b.basisId()))
                .toList();
    return new ProposalDetail(
        p.id(),
        space,
        p.recordId(),
        p.analysisId(),
        p.itemType(),
        action(p),
        p.status(),
        title(p, project(p)),
        target,
        body.path("expectedVersion").isNumber() ? body.path("expectedVersion").asText() : null,
        current == null ? null : Long.toString(current.version()),
        currentValues,
        project(p),
        candidates,
        redacted
            ? List.of()
            : List.of(
                new ProposalDetail.Evidence(
                    p.evidenceId(), p.recordId(), p.quote(), true, p.author(), p.recordedAt())),
        text(body, "relationKind"),
        conflict(p),
        reviews.issue(user, space, p.id()),
        redacted
            ? List.of(
                "title",
                "targetItemId",
                "analysisTargetVersion",
                "currentItemVersion",
                "currentValues",
                "proposedValues",
                "candidates",
                "evidence",
                "relationKind",
                "needsConflictReview",
                "changedFields",
                "clearFields",
                "reviewReasons",
                "comparisonBasis")
            : List.of(),
        p.createdAt(),
        redacted ? List.of() : body.path("changes").propertyNames().stream().sorted().toList(),
        strings(body.path("clearFields")),
        strings(body.path("reviewReasons")),
        basis,
        proposals.decision(space, p.id(), mapper));
  }

  private ProposalAction action(ProposalQueryRepository.View p) {
    return switch (p.operation()) {
      case "CREATE" -> ProposalAction.CREATE_ITEM;
      case "UPDATE" -> ProposalAction.UPDATE_ITEM;
      case "RESOLVE" -> ProposalAction.RESOLVE_QUESTION;
      case "LINK_EVIDENCE" -> ProposalAction.LINK_EVIDENCE;
      default -> throw new IllegalStateException("Unsupported stored operation");
    };
  }

  private JsonNode payload(ProposalQueryRepository.View p) {
    return p.document() == null ? mapper.createObjectNode() : mapper.readTree(p.document());
  }

  private String title(ProposalQueryRepository.View p, ProposalValues projected) {
    return p.contractVersion().equals("v2")
        ? text(payload(p), "title")
        : projected == null ? null : projected.title();
  }

  private Boolean conflict(ProposalQueryRepository.View p) {
    return p.contractVersion().equals("v2") && p.document() != null
        ? "CONFLICT".equals(text(payload(p), "relationKind"))
        : null;
  }

  private String text(JsonNode body, String field) {
    return body.path(field).isString() ? body.path(field).asText() : null;
  }

  private UUID uuid(JsonNode body, String field) {
    String value = text(body, field);
    return value == null ? null : UUID.fromString(value);
  }

  private List<String> strings(JsonNode array) {
    var result = new ArrayList<String>();
    array.forEach(v -> result.add(v.asText()));
    return List.copyOf(result);
  }

  private ProposalQueryRepository.Result authorized(
      Optional<ProposalQueryRepository.Result> query) {
    var result = query.orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    if (!result.authorized()) throw new ApiException(CommonErrorCode.FORBIDDEN);
    if (!result.recordFound()) throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
    return result;
  }

  private UUID recordId(String value) {
    if (value == null) return null;
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException exception) {
      throw invalid("recordId", "원본 ID는 UUID로 입력해주세요.");
    }
  }

  private ProposalStatus status(String value) {
    if (value == null) return ProposalStatus.REVIEW_PENDING;
    try {
      return ProposalStatus.valueOf(value);
    } catch (IllegalArgumentException exception) {
      throw invalid("status", "REVIEW_PENDING, APPLIED, DECLINED, INVALIDATED로 입력해주세요.");
    }
  }

  private ApiException invalid(String field, String reason) {
    return new ApiException(
        CommonErrorCode.INVALID_REQUEST, List.of(new ApiFieldError(field, reason)));
  }
}
