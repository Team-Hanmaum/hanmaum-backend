package com.hanmaum.backend.proposal.repository;

import com.hanmaum.backend.proposal.dto.ProposalItemType;
import com.hanmaum.backend.proposal.dto.ProposalStatus;
import com.hanmaum.backend.record.dto.SourceRecordAuthor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProposalQueryRepository {
  private final JdbcTemplate jdbc;

  public ProposalQueryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Result> find(
      UUID userId,
      UUID spaceId,
      UUID recordId,
      ProposalStatus status,
      Instant beforeTime,
      UUID beforeId,
      UUID proposalId,
      int limit) {
    // Permission, provenance and payload use one snapshot, including empty/invalidated pages.
    // CASE prevents even retained invalid content from leaving the database via this query.
    var rows =
        jdbc.query(
            """
        WITH query AS (
          SELECT CAST(? AS uuid) user_id, CAST(? AS uuid) space_id,
                 CAST(? AS uuid) record_id, CAST(? AS text) status,
                 CAST(? AS timestamptz) before_time, CAST(? AS uuid) before_id,
                 CAST(? AS uuid) proposal_id, CAST(? AS integer) page_limit
        )
        SELECT viewer.id IS NOT NULL AS authorized,
               q.record_id IS NULL OR filter_record.id IS NOT NULL AS record_found, p.*
        FROM query q
        JOIN care_space s ON s.id = q.space_id AND s.lifecycle = 'ACTIVE'
        LEFT JOIN space_membership viewer ON viewer.space_id = s.id
          AND viewer.user_id = q.user_id AND viewer.is_active
        LEFT JOIN source_record filter_record ON filter_record.space_id = s.id
          AND filter_record.id = q.record_id
        LEFT JOIN LATERAL (
          SELECT p.id, a.record_id, a.id AS analysis_id, p.item_type, p.created_at, p.ai_operation, a.contract_version, p.resolution,
                 visibility.available,
                 CASE WHEN visibility.available THEN d.body::text END AS document,
                 CASE WHEN visibility.available THEN e.id END AS evidence_id,
                 CASE WHEN visibility.available THEN e.quote END AS quote,
                 CASE WHEN visibility.available THEN r.recorded_at END AS recorded_at,
                 CASE WHEN visibility.available THEN author.id END AS author_membership_id,
                 CASE WHEN visibility.available THEN author.user_id END AS author_user_id,
                 CASE WHEN visibility.available THEN u.display_name END AS display_name,
                 CASE WHEN visibility.available THEN assignee.id END AS assignee_id
          FROM ai_proposal p
          JOIN analysis_run a ON a.space_id = p.space_id AND a.id = p.run_id
          JOIN source_record r ON r.space_id = a.space_id AND r.id = a.record_id
          JOIN content_document d ON d.space_id = p.space_id AND d.id = p.output_document_id
          JOIN evidence e ON e.space_id = p.space_id AND e.id = p.primary_evidence_id
          LEFT JOIN space_membership author ON author.space_id = r.space_id
            AND author.id = r.author_membership_id
          LEFT JOIN app_user u ON u.id = author.user_id
          LEFT JOIN space_membership assignee ON assignee.space_id = p.space_id
            AND assignee.is_active AND assignee.id = CASE
              WHEN (d.body #>> '{changes,assigneeMembershipId}')
                ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
              THEN (d.body #>> '{changes,assigneeMembershipId}')::uuid END
          CROSS JOIN LATERAL (
            SELECT p.invalidated_at IS NULL
              AND a.status = 'COMPLETED' AND a.invalidated_at IS NULL
              AND r.lifecycle = 'ACTIVE' AND r.version = a.record_version
              AND d.redacted_at IS NULL AND d.body IS NOT NULL
              AND e.redacted_at IS NULL AND e.quote IS NOT NULL
              AND e.record_id = r.id AND e.record_version = r.version
              AND care_document_available(d.id)
              AND (CASE WHEN p.resolution='OPEN' THEN analysis_inputs_valid(a.id)
                ELSE analysis_provenance_valid(a.id) END)
                AS available
          ) visibility
          WHERE p.space_id = s.id AND viewer.id IS NOT NULL
            AND (q.record_id IS NULL OR a.record_id = q.record_id)
            AND (q.proposal_id IS NULL OR p.id = q.proposal_id)
            AND (q.status IS NULL OR (q.status = 'REVIEW_PENDING' AND p.resolution='OPEN' AND visibility.available)
              OR (q.status = 'INVALIDATED' AND p.resolution='OPEN' AND NOT visibility.available)
              OR (q.status IN ('APPLIED','DECLINED') AND p.resolution=q.status))
            AND (q.before_time IS NULL OR (p.created_at, p.id) < (q.before_time, q.before_id))
          ORDER BY p.created_at DESC, p.id DESC LIMIT q.page_limit
        ) p ON true
        ORDER BY p.created_at DESC, p.id DESC
        """,
            this::map,
            userId,
            spaceId,
            recordId,
            status == null ? null : status.name(),
            beforeTime == null ? null : Timestamp.from(beforeTime),
            beforeId,
            proposalId,
            limit);
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(
        new Result(
            rows.getFirst().authorized(),
            rows.getFirst().recordFound(),
            rows.stream().map(Row::proposal).filter(Objects::nonNull).toList()));
  }

  public List<InputItem> inputItems(UUID space, UUID run) {
    return jdbc.query(
        """
        SELECT i.item_id, c.version, c.status, d.body::text document,
               assignee.id assignee_id
        FROM analysis_input_item i
        JOIN care_item c ON c.space_id=i.space_id AND c.id=i.item_id
        JOIN content_document d ON d.space_id=c.space_id AND d.id=c.current_document_id
        LEFT JOIN space_membership assignee ON assignee.space_id=c.space_id AND assignee.is_active
          AND assignee.id=CASE WHEN (d.body #>> '{fields,assigneeMembershipId}')
            ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            THEN (d.body #>> '{fields,assigneeMembershipId}')::uuid END
        WHERE i.space_id=? AND i.run_id=? AND analysis_inputs_valid(i.run_id)
        ORDER BY i.item_id
        """,
        (rs, n) ->
            new InputItem(
                rs.getObject("item_id", UUID.class),
                rs.getLong("version"),
                rs.getString("status"),
                rs.getString("document"),
                rs.getObject("assignee_id", UUID.class)),
        space,
        run);
  }

  public List<com.hanmaum.backend.proposal.dto.ProposalDetail.ComparisonBasis> inputBasis(
      UUID space, UUID run) {
    return jdbc.query(
        """
        SELECT b.basis_id,b.item_id,e.record_id,
          CASE WHEN b.direct_document_id IS NOT NULL THEN 'DIRECT_INPUT' ELSE 'SOURCE_RECORD' END kind,
          CASE WHEN b.direct_document_id IS NOT NULL THEN d.body::text ELSE e.quote END text
        FROM analysis_input_basis b
        LEFT JOIN content_document d ON d.space_id=b.space_id AND d.id=b.direct_document_id
        LEFT JOIN evidence e ON e.space_id=b.space_id AND e.id=b.source_evidence_id
        WHERE b.space_id=? AND b.run_id=? AND analysis_inputs_valid(b.run_id)
        ORDER BY b.basis_id
        """,
        (rs, n) ->
            new com.hanmaum.backend.proposal.dto.ProposalDetail.ComparisonBasis(
                rs.getObject("basis_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getString("kind"),
                rs.getObject("record_id", UUID.class),
                rs.getString("text")),
        space,
        run);
  }

  public record InputItem(UUID id, long version, String status, String document, UUID assigneeId) {}

  public com.hanmaum.backend.proposal.dto.ProposalDetail.Decision decision(
      UUID space, UUID proposal, tools.jackson.databind.ObjectMapper mapper) {
    return jdbc
        .query(
            """
        SELECT b.id,b.decided_at,x.item_id,x.item_version,
          CASE WHEN care_document_available(d.id) THEN d.body::text END body
        FROM proposal_decision x JOIN proposal_decision_batch b ON b.space_id=x.space_id AND b.id=x.batch_id
        LEFT JOIN content_document d ON d.space_id=x.space_id AND d.id=x.document_id
        WHERE x.space_id=? AND x.proposal_id=?
        """,
            (rs, n) ->
                new com.hanmaum.backend.proposal.dto.ProposalDetail.Decision(
                    rs.getObject(1, UUID.class),
                    rs.getTimestamp(2).toInstant(),
                    rs.getObject(3, UUID.class),
                    rs.getString(4),
                    rs.getString(5) == null ? null : mapper.readTree(rs.getString(5))),
            space,
            proposal)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private Row map(ResultSet rs, int index) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    boolean available = rs.getBoolean("available");
    boolean withdrawn = rs.getObject("author_user_id") == null;
    var view =
        id == null
            ? null
            : new View(
                id,
                rs.getObject("record_id", UUID.class),
                rs.getObject("analysis_id", UUID.class),
                ProposalItemType.valueOf(rs.getString("item_type")),
                "OPEN".equals(rs.getString("resolution"))
                    ? (available ? ProposalStatus.REVIEW_PENDING : ProposalStatus.INVALIDATED)
                    : ProposalStatus.valueOf(rs.getString("resolution")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("document"),
                rs.getObject("evidence_id", UUID.class),
                rs.getString("quote"),
                available
                    ? new SourceRecordAuthor(
                        withdrawn ? null : rs.getObject("author_membership_id", UUID.class),
                        withdrawn ? null : rs.getString("display_name"),
                        withdrawn)
                    : null,
                available ? rs.getTimestamp("recorded_at").toInstant() : null,
                rs.getObject("assignee_id", UUID.class),
                rs.getString("ai_operation"),
                rs.getString("contract_version"));
    return new Row(rs.getBoolean("authorized"), rs.getBoolean("record_found"), view);
  }

  private record Row(boolean authorized, boolean recordFound, View proposal) {}

  public record Result(boolean authorized, boolean recordFound, List<View> proposals) {}

  public record View(
      UUID id,
      UUID recordId,
      UUID analysisId,
      ProposalItemType itemType,
      ProposalStatus status,
      Instant createdAt,
      String document,
      UUID evidenceId,
      String quote,
      SourceRecordAuthor author,
      Instant recordedAt,
      UUID assigneeId,
      String operation,
      String contractVersion) {}
}
