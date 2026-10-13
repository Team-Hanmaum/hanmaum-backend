package com.hanmaum.backend.record.repository;

import com.hanmaum.backend.analysis.dto.AnalysisSummary;
import com.hanmaum.backend.analysis.entity.AnalysisStatus;
import com.hanmaum.backend.record.dto.SourceRecordAuthor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SourceRecordQueryRepository {
  private final JdbcTemplate jdbc;

  public SourceRecordQueryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Result> list(
      UUID userId, UUID spaceId, Instant beforeTime, UUID beforeId, int limit) {
    List<Object> args = new ArrayList<>(List.of(userId, userId));
    String continuation = "";
    if (beforeTime != null) {
      continuation = " AND (r.recorded_at, r.id) < (?, ?) ";
      args.add(Timestamp.from(beforeTime));
      args.add(beforeId);
    }
    args.add(limit);
    args.add(spaceId);
    // The permission gate and data share one statement/snapshot, including an empty page.
    return result(
        jdbc.query(
            """
        SELECT viewer.id AS viewer_id, viewer.id = s.owner_membership_id AS viewer_owner,
               r.id, r.body, r.recorded_at, r.version, r.author_membership_id,
               author.user_id AS author_user_id, u.display_name, author.user_id = ? AS is_mine,
               a.id AS analysis_id, a.status AS analysis_status,
               ARRAY(SELECT p.id FROM ai_proposal p WHERE p.run_id = a.id AND a.status = 'COMPLETED'
                 AND p.invalidated_at IS NULL ORDER BY p.ordinal_no) AS proposal_ids
        FROM care_space s
        LEFT JOIN space_membership viewer ON viewer.space_id = s.id AND viewer.user_id = ? AND viewer.is_active
        LEFT JOIN LATERAL (
          SELECT r.* FROM source_record r
          WHERE r.space_id = s.id AND r.lifecycle = 'ACTIVE' AND viewer.id IS NOT NULL
        """
                + continuation
                + """
          ORDER BY r.recorded_at DESC, r.id DESC LIMIT ?
        ) r ON true
        LEFT JOIN space_membership author ON author.space_id = s.id AND author.id = r.author_membership_id
        LEFT JOIN app_user u ON u.id = author.user_id
        LEFT JOIN LATERAL (SELECT id, status FROM analysis_run WHERE record_id = r.id
          ORDER BY attempt_no DESC LIMIT 1) a ON true
        WHERE s.id = ? AND s.lifecycle = 'ACTIVE'
        ORDER BY r.recorded_at DESC, r.id DESC
        """,
            this::map,
            args.toArray()));
  }

  public Optional<Result> detail(UUID userId, UUID spaceId, UUID recordId) {
    return result(
        jdbc.query(
            """
        SELECT viewer.id AS viewer_id, viewer.id = s.owner_membership_id AS viewer_owner,
               r.id, r.body, r.recorded_at, r.version, r.author_membership_id,
               author.user_id AS author_user_id, u.display_name, author.user_id = ? AS is_mine,
               a.id AS analysis_id, a.status AS analysis_status,
               ARRAY(SELECT p.id FROM ai_proposal p WHERE p.run_id = a.id AND a.status = 'COMPLETED'
                 AND p.invalidated_at IS NULL ORDER BY p.ordinal_no) AS proposal_ids
        FROM care_space s
        LEFT JOIN space_membership viewer ON viewer.space_id = s.id AND viewer.user_id = ? AND viewer.is_active
        LEFT JOIN source_record r ON r.space_id = s.id AND r.id = ? AND r.lifecycle = 'ACTIVE' AND viewer.id IS NOT NULL
        LEFT JOIN space_membership author ON author.space_id = s.id AND author.id = r.author_membership_id
        LEFT JOIN app_user u ON u.id = author.user_id
        LEFT JOIN LATERAL (SELECT id, status FROM analysis_run WHERE record_id = r.id
          ORDER BY attempt_no DESC LIMIT 1) a ON true
        WHERE s.id = ? AND s.lifecycle = 'ACTIVE'
        """,
            this::map,
            userId,
            userId,
            recordId,
            spaceId));
  }

  private Row map(ResultSet rs, int row) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    boolean withdrawn = rs.getObject("author_user_id") == null;
    var record =
        id == null
            ? null
            : new View(
                id,
                rs.getString("body"),
                new SourceRecordAuthor(
                    withdrawn ? null : rs.getObject("author_membership_id", UUID.class),
                    withdrawn ? null : rs.getString("display_name"),
                    withdrawn),
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getLong("version"),
                rs.getBoolean("is_mine"),
                rs.getObject("analysis_id") == null
                    ? null
                    : new AnalysisSummary(
                        rs.getObject("analysis_id", UUID.class),
                        AnalysisStatus.valueOf(rs.getString("analysis_status"))),
                Arrays.asList((UUID[]) rs.getArray("proposal_ids").getArray()));
    return new Row(rs.getObject("viewer_id") != null, rs.getBoolean("viewer_owner"), record);
  }

  private Optional<Result> result(List<Row> rows) {
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(
        new Result(
            rows.getFirst().authorized(),
            rows.getFirst().owner(),
            rows.stream().map(Row::record).filter(java.util.Objects::nonNull).toList()));
  }

  private record Row(boolean authorized, boolean owner, View record) {}

  public record Result(boolean authorized, boolean owner, List<View> records) {}

  public record View(
      UUID id,
      String content,
      SourceRecordAuthor author,
      Instant recordedAt,
      long version,
      boolean isMine,
      AnalysisSummary analysis,
      List<UUID> proposalIds) {}
}
