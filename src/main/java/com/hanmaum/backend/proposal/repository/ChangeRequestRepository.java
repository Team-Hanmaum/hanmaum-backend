package com.hanmaum.backend.proposal.repository;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.proposal.dto.ProposalDecisionRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Caller holds the space write lock for mutations; queries run in a coherent read transaction. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class ChangeRequestRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public ChangeRequestRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public UUID authorize(UUID actor, UUID space) {
    var result =
        jdbc.query(
            """
        SELECT m.id FROM care_space s LEFT JOIN space_membership m
          ON m.space_id=s.id AND m.user_id=? AND m.is_active
        WHERE s.id=? AND s.lifecycle='ACTIVE'
        """,
            (rs, n) -> rs.getObject(1, UUID.class),
            actor,
            space);
    if (result.isEmpty()) throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
    if (result.getFirst() == null) throw new ApiException(CommonErrorCode.FORBIDDEN);
    return result.getFirst();
  }

  public UUID currentOwner(UUID actor, UUID space) {
    return jdbc
        .query(
            """
        SELECT m.id FROM care_space s JOIN space_membership m
          ON m.space_id=s.id AND m.id=s.owner_membership_id AND m.is_active AND m.user_id=?
        WHERE s.id=? AND s.lifecycle='ACTIVE'
        """,
            (rs, n) -> rs.getObject(1, UUID.class),
            actor,
            space)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public boolean reviewable(UUID space, UUID entry) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS(SELECT 1 FROM change_request_entry e WHERE e.space_id=? AND e.id=?
          AND e.status='PENDING' AND change_request_entry_invalid_reason(e.id) IS NULL)
        """,
            Boolean.class,
            space,
            entry));
  }

  public List<UUID> evidenceIds(UUID space, UUID entry) {
    return jdbc.query(
        """
        SELECT evidence.id FROM change_request_entry e
        JOIN ai_proposal p ON p.space_id=e.space_id AND p.id=e.proposal_id
        JOIN evidence ON evidence.space_id=p.space_id AND evidence.id=p.primary_evidence_id
        JOIN source_record r ON r.space_id=evidence.space_id AND r.id=evidence.record_id
        WHERE e.space_id=? AND e.id=? AND care_document_available(e.request_document_id)
          AND evidence.quote IS NOT NULL AND evidence.redacted_at IS NULL
          AND r.lifecycle='ACTIVE' AND r.version=evidence.record_version
        """,
        (rs, n) -> rs.getObject(1, UUID.class),
        space,
        entry);
  }

  public void linkAppliedDocument(UUID space, UUID document, UUID parent) {
    jdbc.update(
        "INSERT INTO care_item_document_parent(space_id,document_id,parent_document_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",
        space,
        document,
        parent);
  }

  public void createRequest(UUID space, UUID id, UUID member, Instant now) {
    jdbc.update(
        "INSERT INTO change_request(id,space_id,submitted_by_membership_id,submitted_at) VALUES (?,?,?,?)",
        id,
        space,
        member,
        Timestamp.from(now));
  }

  public UUID document(
      UUID space,
      JsonNode values,
      UUID proposalDocument,
      UUID run,
      UUID targetDocument,
      Instant now) {
    UUID id = UUID.randomUUID();
    var body = mapper.createObjectNode();
    body.set("values", values == null ? mapper.nullNode() : values);
    jdbc.update(
        "INSERT INTO content_document(id,space_id,kind,body,created_at) VALUES (?,?,'CHANGE_REQUEST',?::jsonb,?)",
        id,
        space,
        mapper.writeValueAsString(body),
        Timestamp.from(now));
    jdbc.update(
        """
        INSERT INTO care_item_document_parent(space_id,document_id,parent_document_id)
        SELECT ?,?,parent FROM (
          SELECT CAST(? AS uuid) parent UNION SELECT CAST(? AS uuid)
          UNION SELECT document_id FROM analysis_input_item WHERE space_id=? AND run_id=?
          UNION SELECT direct_document_id FROM analysis_input_basis WHERE space_id=? AND run_id=?
        ) parents WHERE parent IS NOT NULL
        """,
        space,
        id,
        proposalDocument,
        targetDocument,
        space,
        run,
        space,
        run);
    return id;
  }

  public void insertEntry(
      UUID space,
      UUID request,
      UUID id,
      int index,
      ProposalDecisionRequest.Entry e,
      UUID document) {
    jdbc.update(
        """
        INSERT INTO change_request_entry(id,space_id,request_id,ordinal_no,proposal_id,action,target_item_id,expected_item_version,request_document_id)
        VALUES (?,?,?,?,?,?,?,?,?)
        """,
        id,
        space,
        request,
        index,
        e.proposalId(),
        e.action().name(),
        e.targetItemId(),
        e.expectedItemVersion(),
        document);
  }

  private static final String ENTRY_SELECT =
      """
      SELECT e.*,r.submitted_by_membership_id,r.submitted_at,m.is_active,
        change_request_entry_invalid_reason(e.id) invalid_reason,
        CASE WHEN care_document_available(d.id) THEN (d.body->'values')::text END requested_values,
        b.decided_by_membership_id,b.decided_at,
        CASE WHEN care_document_available(d.id) AND care_document_available(applied.id) THEN applied.body::text END applied_values
      FROM change_request_entry e
      JOIN change_request r ON r.space_id=e.space_id AND r.id=e.request_id
      JOIN space_membership m ON m.space_id=r.space_id AND m.id=r.submitted_by_membership_id
      JOIN content_document d ON d.space_id=e.space_id AND d.id=e.request_document_id
      LEFT JOIN change_request_decision_batch b ON b.space_id=e.space_id AND b.id=e.decision_batch_id
      LEFT JOIN content_document applied ON applied.space_id=e.space_id AND applied.id=e.result_document_id
      """;

  public Optional<Entry> lock(UUID space, UUID id) {
    return jdbc
        .query(
            ENTRY_SELECT + " WHERE e.space_id=? AND e.id=? FOR UPDATE OF e", this::entry, space, id)
        .stream()
        .findFirst();
  }

  public List<Entry> entries(UUID space, UUID request) {
    return jdbc.query(
        ENTRY_SELECT + " WHERE e.space_id=? AND e.request_id=? ORDER BY e.ordinal_no",
        this::entry,
        space,
        request);
  }

  private Entry entry(ResultSet rs, int row) throws SQLException {
    String reason = rs.getString("invalid_reason");
    String stored = rs.getString("status");
    return new Entry(
        rs.getObject("id", UUID.class),
        rs.getObject("request_id", UUID.class),
        rs.getObject("proposal_id", UUID.class),
        rs.getString("action"),
        rs.getObject("target_item_id", UUID.class),
        rs.getObject("expected_item_version", Long.class),
        rs.getObject("request_document_id", UUID.class),
        rs.getString("requested_values"),
        stored.equals("PENDING") && reason != null ? "INVALIDATED" : stored,
        reason,
        rs.getObject("submitted_by_membership_id", UUID.class),
        rs.getBoolean("is_active"),
        rs.getTimestamp("submitted_at").toInstant(),
        rs.getObject("result_item_id", UUID.class),
        rs.getString("result_item_version"),
        rs.getObject("result_document_id", UUID.class),
        rs.getObject("decision_batch_id", UUID.class),
        rs.getObject("decided_by_membership_id", UUID.class),
        instant(rs, "decided_at"),
        rs.getString("applied_values"));
  }

  private static final String HEADER_SELECT =
      """
      SELECT r.*,m.user_id,CASE WHEN m.is_active THEN u.display_name END display_name,NOT m.is_active withdrawn
      FROM change_request r JOIN space_membership m ON m.space_id=r.space_id AND m.id=r.submitted_by_membership_id
      LEFT JOIN app_user u ON u.id=m.user_id
      """;

  public Optional<Header> header(UUID space, UUID request) {
    return jdbc
        .query(HEADER_SELECT + " WHERE r.space_id=? AND r.id=?", this::header, space, request)
        .stream()
        .findFirst();
  }

  public List<Header> list(
      UUID space, String status, Instant cursorTime, UUID cursorId, int limit) {
    String sql = HEADER_SELECT + " WHERE r.space_id=? ";
    List<Object> params = new ArrayList<>();
    params.add(space);
    if (status != null) {
      sql +=
          """
          AND EXISTS (SELECT 1 FROM change_request_entry e WHERE e.space_id=r.space_id AND e.request_id=r.id
            AND CASE WHEN e.status='PENDING' AND change_request_entry_invalid_reason(e.id) IS NOT NULL
              THEN 'INVALIDATED' ELSE e.status END = ?)
          """;
      params.add(status);
    }
    if (cursorTime != null) {
      sql += " AND (r.submitted_at,r.id)<(?,CAST(? AS uuid))";
      params.add(Timestamp.from(cursorTime));
      params.add(cursorId);
    }
    sql += " ORDER BY r.submitted_at DESC,r.id DESC LIMIT ?";
    params.add(limit);
    return jdbc.query(sql, this::header, params.toArray());
  }

  private Header header(ResultSet rs, int row) throws SQLException {
    return new Header(
        rs.getObject("id", UUID.class),
        rs.getObject("submitted_by_membership_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("display_name"),
        rs.getBoolean("withdrawn"),
        rs.getTimestamp("submitted_at").toInstant());
  }

  public boolean hasPending(UUID space, UUID member, UUID proposal) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS(SELECT 1 FROM change_request_entry e JOIN change_request r ON r.id=e.request_id AND r.space_id=e.space_id
          WHERE e.space_id=? AND r.submitted_by_membership_id=? AND e.proposal_id=?
          AND e.status='PENDING' AND change_request_entry_invalid_reason(e.id) IS NULL)
        """,
            Boolean.class,
            space,
            member,
            proposal));
  }

  public void batch(UUID space, UUID id, UUID member, String decision, Instant now) {
    jdbc.update(
        "INSERT INTO change_request_decision_batch(id,space_id,decided_by_membership_id,decision,decided_at) VALUES (?,?,?,?,?)",
        id,
        space,
        member,
        decision,
        Timestamp.from(now));
  }

  public void decide(
      UUID space,
      UUID id,
      UUID batch,
      String status,
      UUID resultItem,
      Long resultVersion,
      UUID resultDocument) {
    int changed =
        jdbc.update(
            """
        UPDATE change_request_entry SET status=?,decision_batch_id=?,decision_ordinal_no=(
          SELECT count(*) FROM change_request_entry WHERE decision_batch_id=?),
          result_item_id=?,result_item_version=?,result_document_id=?
        WHERE space_id=? AND id=? AND status='PENDING'
        """,
            status,
            batch,
            batch,
            resultItem,
            resultVersion,
            resultDocument,
            space,
            id);
    if (changed != 1)
      throw new IllegalStateException("Decision lost its locked change request entry");
  }

  public JsonNode submissionResult(UUID space, UUID request) {
    var h =
        header(space, request)
            .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    var out =
        mapper
            .createObjectNode()
            .put("requestId", request.toString())
            .put("submittedAt", h.submittedAt().toString());
    var items = out.putArray("entries");
    // Replay is the immutable submission result, not a new query of later decision state.
    jdbc.query(
            "SELECT id FROM change_request_entry WHERE space_id=? AND request_id=? ORDER BY ordinal_no",
            (rs, n) -> rs.getObject(1, UUID.class),
            space,
            request)
        .forEach(
            id -> items.addObject().put("requestEntryId", id.toString()).put("status", "PENDING"));
    return out;
  }

  public JsonNode decisionResult(UUID space, UUID batch) {
    var at =
        jdbc.queryForObject(
            "SELECT decided_at FROM change_request_decision_batch WHERE space_id=? AND id=?",
            (rs, n) -> rs.getTimestamp(1).toInstant(),
            space,
            batch);
    String decision =
        jdbc.queryForObject(
            "SELECT decision FROM change_request_decision_batch WHERE space_id=? AND id=?",
            String.class,
            space,
            batch);
    var out = mapper.createObjectNode().put("batchId", batch.toString());
    if ("APPLIED".equals(decision)) {
      out.put("appliedAt", at.toString());
      var results = out.putArray("results");
      jdbc.query(
          "SELECT id,result_item_id,result_item_version FROM change_request_entry WHERE space_id=? AND decision_batch_id=? ORDER BY decision_ordinal_no",
          (rs, n) -> {
            results
                .addObject()
                .put("requestEntryId", rs.getString(1))
                .put("itemId", rs.getString(2))
                .put("itemVersion", rs.getString(3));
            return 0;
          },
          space,
          batch);
    } else {
      out.put("decidedAt", at.toString());
      var ids =
          out.putArray(
              "DECLINED".equals(decision) ? "declinedRequestEntryIds" : "canceledRequestEntryIds");
      jdbc.query(
              "SELECT id FROM change_request_entry WHERE space_id=? AND decision_batch_id=? ORDER BY decision_ordinal_no",
              (rs, n) -> rs.getString(1),
              space,
              batch)
          .forEach(ids::add);
    }
    return out;
  }

  private Instant instant(ResultSet rs, String field) throws SQLException {
    Timestamp value = rs.getTimestamp(field);
    return value == null ? null : value.toInstant();
  }

  public record Entry(
      UUID id,
      UUID requestId,
      UUID proposalId,
      String action,
      UUID targetItemId,
      Long expectedItemVersion,
      UUID documentId,
      String values,
      String status,
      String invalidReason,
      UUID memberId,
      boolean memberActive,
      Instant submittedAt,
      UUID resultItemId,
      String resultVersion,
      UUID resultDocumentId,
      UUID decisionBatchId,
      UUID decidedBy,
      Instant decidedAt,
      String appliedValues) {
    @Override
    public String toString() {
      return "ChangeRequestEntry[id=" + id + "]";
    }
  }

  public record Header(
      UUID id,
      UUID memberId,
      UUID userId,
      String displayName,
      boolean withdrawn,
      Instant submittedAt) {}
}
