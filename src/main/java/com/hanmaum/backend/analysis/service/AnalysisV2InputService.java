package com.hanmaum.backend.analysis.service;

import com.hanmaum.backend.ai.client.v2.*;
import com.hanmaum.backend.ai.code.AiFailureCode;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisRequest;
import com.hanmaum.backend.analysis.entity.AnalysisRun;
import com.hanmaum.backend.careitem.service.CareItemV2Values;
import com.hanmaum.backend.record.entity.SourceRecord;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Bounded, claim-time input capture. All content stays in the call DTO, not execution metadata. */
@Service
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = AiV2Exception.class)
public class AnalysisV2InputService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final AiV2Properties settings;

  public AnalysisV2InputService(JdbcTemplate jdbc, ObjectMapper mapper, AiV2Properties settings) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.settings = settings;
  }

  public AiV2AnalysisRequest capture(AnalysisRun run, SourceRecord source) {
    if (source.body() == null || source.body().length() > 10_000) throw invalid();
    var contract = new AiV2Contract(settings.requireLimits());
    ObjectNode root = mapper.createObjectNode();
    root.put("requestId", run.requestId().toString());
    root.put("careSpaceId", run.spaceId().toString());
    root.put("recordId", run.recordId().toString());
    root.put("content", source.body());
    root.put("recordedAt", run.inputRecordedAt().toString());
    root.put("timezone", "Asia/Seoul");
    var candidates = root.putArray("candidates");
    // Fetch metadata first, never a potentially unbounded collection of document bodies.
    var items =
        jdbc.query(
            """
        SELECT i.id,i.version,i.item_type,i.status,i.current_document_id,assignee.id assignee_id
        FROM care_item i JOIN content_document d ON d.space_id=i.space_id AND d.id=i.current_document_id
        LEFT JOIN space_membership assignee ON i.item_type='TASK'
          AND assignee.space_id=i.space_id AND assignee.is_active
          AND assignee.id=CASE WHEN (d.body #>> '{fields,assigneeMembershipId}')
            ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            THEN (d.body #>> '{fields,assigneeMembershipId}')::uuid END
        WHERE i.space_id=? AND i.visibility='VISIBLE' AND NOT i.needs_reconfirmation
          AND d.body IS NOT NULL AND d.redacted_at IS NULL
          AND EXISTS (SELECT 1 FROM care_item_basis b
            LEFT JOIN content_document direct ON direct.space_id=b.space_id AND direct.id=b.direct_document_id
            LEFT JOIN evidence e ON e.space_id=b.space_id AND e.id=b.source_evidence_id
            LEFT JOIN source_record r ON r.space_id=e.space_id AND r.id=e.record_id
            WHERE b.space_id=i.space_id AND b.item_id=i.id AND
              ((direct.body IS NOT NULL AND direct.redacted_at IS NULL) OR
               (e.quote IS NOT NULL AND e.redacted_at IS NULL AND r.lifecycle='ACTIVE' AND r.version=e.record_version)))
          AND NOT EXISTS (SELECT 1 FROM care_item_basis b
            LEFT JOIN evidence e ON e.space_id=b.space_id AND e.id=b.source_evidence_id
            LEFT JOIN source_record r ON r.space_id=e.space_id AND r.id=e.record_id
            LEFT JOIN content_document direct ON direct.space_id=b.space_id AND direct.id=b.direct_document_id
            WHERE b.space_id=i.space_id AND b.item_id=i.id
              AND ((b.source_evidence_id IS NOT NULL AND
                (e.quote IS NULL OR e.redacted_at IS NOT NULL OR r.id IS NULL
                  OR r.lifecycle<>'ACTIVE' OR r.version<>e.record_version))
                OR (b.direct_document_id IS NOT NULL AND
                  (direct.body IS NULL OR direct.redacted_at IS NOT NULL))))
        ORDER BY i.created_at DESC,i.id DESC LIMIT 101
        """,
            (rs, n) ->
                new Item(
                    rs.getObject(1, UUID.class),
                    rs.getLong(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getObject(5, UUID.class),
                    rs.getObject(6, UUID.class)),
            run.spaceId());
    if (items.size() > 100) throw invalid();
    var captured = new ArrayList<Link>();
    long units = stringUnits(root);
    if (units > 100_000) throw invalid();
    int totalBasis = 0;
    for (var item : items) {
      String body = document(run.spaceId(), item.document(), 1_048_576);
      ObjectNode candidate = candidates.addObject();
      candidate.put("itemId", item.id().toString());
      candidate.put("version", item.version());
      candidate.put("type", item.type());
      ObjectNode values;
      try {
        values =
            CareItemV2Values.project(
                mapper, item.type(), item.status(), mapper.readTree(body), item.assignee());
      } catch (RuntimeException e) {
        throw invalid();
      }
      for (var value : values) {
        if (!value.isString() && !value.isNull()) throw invalid();
        if (value.isString() && value.asText().length() > 10_000) throw invalid();
      }
      candidate.set("currentValues", values);
      units += stringUnits(values) + item.id().toString().length() + item.type().length();
      if (units > 100_000) throw invalid();
      var basis = candidate.putArray("basis");
      var links =
          jdbc.query(
              """
          SELECT b.id,b.direct_document_id,b.source_evidence_id,e.record_id,e.record_version
          FROM care_item_basis b
          LEFT JOIN content_document d ON d.space_id=b.space_id AND d.id=b.direct_document_id
          LEFT JOIN evidence e ON e.space_id=b.space_id AND e.id=b.source_evidence_id
          LEFT JOIN source_record r ON r.space_id=e.space_id AND r.id=e.record_id
          WHERE b.space_id=? AND b.item_id=? AND
            ((d.body IS NOT NULL AND d.redacted_at IS NULL) OR
             (e.quote IS NOT NULL AND e.redacted_at IS NULL AND r.lifecycle='ACTIVE' AND r.version=e.record_version))
          ORDER BY b.id LIMIT 21
          """,
              (rs, n) ->
                  new Link(
                      item.id(),
                      rs.getObject(1, UUID.class),
                      rs.getObject(2, UUID.class),
                      rs.getObject(3, UUID.class),
                      rs.getObject(4, UUID.class),
                      (Long) rs.getObject(5)),
              run.spaceId(),
              item.id());
      totalBasis += links.size();
      if (links.size() > 20 || totalBasis > 500) throw invalid();
      for (var link : links) {
        String text =
            link.document() != null
                ? directText(run.spaceId(), link.document())
                : jdbc.queryForObject(
                    "SELECT CASE WHEN octet_length(quote)<=80000 THEN quote END FROM evidence WHERE space_id=? AND id=?",
                    String.class,
                    run.spaceId(),
                    link.evidence());
        if (text == null || text.length() > 20_000) throw invalid();
        units +=
            text.length()
                + link.id().toString().length()
                + (link.document() == null ? "SOURCE_RECORD" : "DIRECT_INPUT").length();
        if (units > 100_000) throw invalid();
        basis
            .addObject()
            .put("basisId", link.id().toString())
            .put("kind", link.document() == null ? "SOURCE_RECORD" : "DIRECT_INPUT")
            .put("text", text);
      }
      captured.addAll(links);
    }
    AiV2AnalysisRequest request;
    try {
      request = contract.parseRequest(mapper.writeValueAsBytes(root));
      contract.encodeRequest(request);
    } catch (RuntimeException e) {
      throw invalid();
    }
    for (var item : items)
      jdbc.update(
          """
        INSERT INTO analysis_input_item (space_id,run_id,item_id,item_version,document_id,item_type,item_status,source_evidence_ids)
        VALUES (?,?,?,?,?,?,?,ARRAY[]::uuid[])
        """,
          run.spaceId(),
          run.id(),
          item.id(),
          item.version(),
          item.document(),
          item.type(),
          item.status());
    for (var link : captured) {
      jdbc.update(
          "INSERT INTO analysis_input_basis VALUES (?,?,?,?,?,?)",
          run.spaceId(),
          run.id(),
          link.item(),
          link.id(),
          link.document(),
          link.evidence());
      if (link.record() != null)
        jdbc.update(
            "INSERT INTO analysis_input_source VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
            run.spaceId(),
            run.id(),
            link.record(),
            link.version());
    }
    jdbc.update("UPDATE analysis_run SET input_captured=TRUE WHERE id=?", run.id());
    return request;
  }

  private String directText(UUID space, UUID document) {
    String stored = document(space, document, 80_000);
    try {
      return mapper.writeValueAsString(mapper.readTree(stored));
    } catch (tools.jackson.core.JacksonException exception) {
      throw invalid();
    }
  }

  private String document(UUID space, UUID id, int maxBytes) {
    String body =
        jdbc.queryForObject(
            "SELECT CASE WHEN octet_length(body::text)<=? AND redacted_at IS NULL THEN body::text END FROM content_document WHERE space_id=? AND id=?",
            String.class,
            maxBytes,
            space,
            id);
    if (body == null) throw invalid();
    return body;
  }

  private long stringUnits(tools.jackson.databind.JsonNode n) {
    if (n.isString()) return n.asText().length();
    long sum = 0;
    if (n.isObject() || n.isArray()) for (var child : n) sum += stringUnits(child);
    return sum;
  }

  private static AiV2Exception invalid() {
    return new AiV2Exception(AiFailureCode.AI_INTEGRATION_ERROR);
  }

  private record Item(
      UUID id, long version, String type, String status, UUID document, UUID assignee) {}

  private record Link(
      UUID item, UUID id, UUID document, UUID evidence, UUID record, Long version) {}
}
