package com.hanmaum.backend.proposal.service;

import com.hanmaum.backend.careitem.dto.CareItemDetail;
import com.hanmaum.backend.careitem.repository.CareItemQueryRepository;
import com.hanmaum.backend.careitem.service.CareItemInput;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.proposal.repository.ChangeRequestRepository;
import com.hanmaum.backend.proposal.repository.ChangeRequestRepository.Entry;
import com.hanmaum.backend.proposal.repository.ChangeRequestRepository.Header;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ChangeRequestQueryService {
  private static final Set<String> STATUSES =
      Set.of("PENDING", "APPLIED", "DECLINED", "CANCELED", "INVALIDATED");
  private final ChangeRequestRepository requests;
  private final CareItemQueryRepository items;
  private final ChangeRequestReviewToken tokens;
  private final ChangeRequestCursor cursors;
  private final ObjectMapper mapper;

  public ChangeRequestQueryService(
      ChangeRequestRepository requests,
      CareItemQueryRepository items,
      ChangeRequestReviewToken tokens,
      ChangeRequestCursor cursors,
      ObjectMapper mapper) {
    this.requests = requests;
    this.items = items;
    this.tokens = tokens;
    this.cursors = cursors;
    this.mapper = mapper;
  }

  public JsonNode list(UUID actor, UUID space, String status, String cursor, int size) {
    String filter = status == null ? "PENDING" : status;
    if (!STATUSES.contains(filter)) throw CareItemInput.invalid("status", "요청 상태를 확인해주세요.");
    if (size < 1 || size > 50) throw CareItemInput.invalid("size", "조회 개수는 1 이상 50 이하로 입력해주세요.");
    requests.authorize(actor, space);
    var position = cursors.decode(space, filter, cursor);
    var found =
        requests.list(
            space,
            filter,
            position == null ? null : position.submittedAt(),
            position == null ? null : position.requestId(),
            size + 1);
    boolean hasNext = found.size() > size;
    var page = found.stream().limit(size).toList();
    var out = mapper.createObjectNode().put("hasNext", hasNext);
    out.put(
        "nextCursor",
        hasNext
            ? cursors.encode(space, filter, page.getLast().submittedAt(), page.getLast().id())
            : null);
    var rows = out.putArray("items");
    for (var h : page) {
      var entries = requests.entries(space, h.id());
      var row = header(h, entries);
      var array = row.putArray("entries");
      for (var entry : entries) array.add(summary(entry));
      rows.add(row);
    }
    return out;
  }

  public JsonNode detail(UUID actor, UUID space, UUID request) {
    requests.authorize(actor, space);
    Header h =
        requests
            .header(space, request)
            .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    var entries = requests.entries(space, request);
    var out = header(h, entries);
    var rows = out.putArray("entries");
    for (var e : entries) {
      var row = summary(e);
      row.put("proposalId", e.proposalId().toString());
      row.put(
          "expectedItemVersion",
          e.expectedItemVersion() == null ? null : e.expectedItemVersion().toString());
      row.set(
          "requestedValues", e.values() == null ? mapper.nullNode() : mapper.readTree(e.values()));
      row.put("reviewToken", tokens.issue(actor, space, e));
      row.putNull("currentItemVersion");
      row.putNull("currentValues");
      var removed = row.putArray("redactedFields");
      if (e.values() == null) removed.add("requestedValues");
      if (e.targetItemId() != null) {
        // A missing target is a valid historical request view, not a nested transactional error.
        // Keep the repository's authorization and redacted public projection; never reload raw
        // body.
        var target =
            items
                .read(actor, space, e.targetItemId(), null, null)
                .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!target.authorized()) throw new ApiException(CommonErrorCode.FORBIDDEN);
        if (!target.items().isEmpty()) {
          CareItemDetail item = target.items().getFirst();
          row.put("currentItemVersion", item.version());
          if ("VISIBLE".equals(item.visibility())) {
            var current =
                mapper
                    .createObjectNode()
                    .put("title", item.title())
                    .put("description", item.description())
                    .put("status", item.status())
                    .put("confirmedAnswer", item.confirmedAnswer());
            current.set("fields", item.fields());
            row.set("currentValues", current);
            for (String field : item.redactedFields()) removed.add("currentValues." + field);
          }
        }
      }
      var evidence = row.putArray("evidenceIds");
      if (e.values() != null)
        requests.evidenceIds(space, e.id()).forEach(id -> evidence.add(id.toString()));
      if (e.decisionBatchId() == null) row.putNull("decision");
      else {
        var decision =
            mapper
                .createObjectNode()
                .put("batchId", e.decisionBatchId().toString())
                .put(
                    "decidedByMembershipId",
                    e.decidedBy() == null ? null : e.decidedBy().toString())
                .put("decidedAt", e.decidedAt() == null ? null : e.decidedAt().toString())
                .put("itemId", e.resultItemId() == null ? null : e.resultItemId().toString())
                .put("itemVersion", e.resultVersion());
        decision.set(
            "appliedValues",
            e.appliedValues() == null ? mapper.nullNode() : mapper.readTree(e.appliedValues()));
        row.set("decision", decision);
        if ("APPLIED".equals(e.status()) && e.appliedValues() == null)
          removed.add("decision.appliedValues");
      }
      rows.add(row);
    }
    return out;
  }

  private ObjectNode header(Header h, List<Entry> entries) {
    var row =
        mapper
            .createObjectNode()
            .put("requestId", h.id().toString())
            .put("submittedAt", h.submittedAt().toString());
    row.set(
        "submittedBy",
        mapper
            .createObjectNode()
            .put("membershipId", h.memberId().toString())
            .put("displayName", h.displayName())
            .put("withdrawn", h.withdrawn()));
    var counts = row.putObject("counts");
    for (String status : List.of("PENDING", "APPLIED", "DECLINED", "CANCELED", "INVALIDATED"))
      counts.put(
          status.toLowerCase(Locale.ROOT),
          entries.stream().filter(e -> e.status().equals(status)).count());
    return row;
  }

  private ObjectNode summary(Entry e) {
    return mapper
        .createObjectNode()
        .put("requestEntryId", e.id().toString())
        .put("action", e.action())
        .put("targetItemId", e.targetItemId() == null ? null : e.targetItemId().toString())
        .put("status", e.status())
        .put("invalidReason", e.invalidReason());
  }
}
