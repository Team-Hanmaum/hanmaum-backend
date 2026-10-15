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
          SELECT p.id, a.record_id, a.id AS analysis_id, p.item_type, p.created_at,
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
            AND assignee.is_active AND assignee.id::text = d.body #>> '{changes,assigneeMembershipId}'
          CROSS JOIN LATERAL (
            SELECT p.invalidated_at IS NULL AND p.resolution = 'OPEN'
              AND a.status = 'COMPLETED' AND a.invalidated_at IS NULL
              AND r.lifecycle = 'ACTIVE' AND r.version = a.record_version
              AND d.redacted_at IS NULL AND d.body IS NOT NULL
              AND e.redacted_at IS NULL AND e.quote IS NOT NULL
              AND e.record_id = r.id AND e.record_version = r.version
              AND EXISTS (SELECT 1 FROM analysis_input_source i
                WHERE i.space_id = p.space_id AND i.run_id = a.id AND i.source_record_id = r.id
                  AND i.source_version = r.version)
              AND NOT EXISTS (
                SELECT 1 FROM analysis_input_source i
                LEFT JOIN source_record dependency ON dependency.space_id = i.space_id
                  AND dependency.id = i.source_record_id
                WHERE i.run_id = a.id AND (dependency.id IS NULL
                  OR dependency.lifecycle <> 'ACTIVE' OR dependency.version <> i.source_version)
              ) AS available
          ) visibility
          WHERE p.space_id = s.id AND viewer.id IS NOT NULL
            AND (q.record_id IS NULL OR a.record_id = q.record_id)
            AND (q.proposal_id IS NULL OR p.id = q.proposal_id)
            AND (q.status IS NULL OR (q.status = 'REVIEW_PENDING' AND visibility.available)
              OR (q.status = 'INVALIDATED' AND NOT visibility.available))
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
                available ? ProposalStatus.REVIEW_PENDING : ProposalStatus.INVALIDATED,
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
                rs.getObject("assignee_id", UUID.class));
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
      UUID assigneeId) {}
}
