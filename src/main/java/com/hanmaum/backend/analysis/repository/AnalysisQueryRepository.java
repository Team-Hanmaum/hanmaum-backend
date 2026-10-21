package com.hanmaum.backend.analysis.repository;

import com.hanmaum.backend.analysis.code.AnalysisFailureCode;
import com.hanmaum.backend.analysis.dto.AnalysisDetail;
import com.hanmaum.backend.analysis.entity.AnalysisStatus;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnalysisQueryRepository {
  private final JdbcTemplate jdbc;

  public AnalysisQueryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Result> find(UUID user, UUID space, UUID record, UUID analysis) {
    return jdbc
        .query(
            """
        SELECT viewer.id AS viewer_id, a.*,
          ARRAY(SELECT p.id FROM ai_proposal p WHERE p.run_id = a.id
            AND p.invalidated_at IS NULL AND analysis_inputs_valid(a.id) AND a.status = 'COMPLETED' ORDER BY ordinal_no) AS proposal_ids,
          a.status = 'FAILED' AND a.id = (SELECT id FROM analysis_run
            WHERE record_id = r.id ORDER BY attempt_no DESC LIMIT 1) AS can_retry
        FROM care_space s
        LEFT JOIN space_membership viewer ON viewer.space_id = s.id AND viewer.user_id = ? AND viewer.is_active
        LEFT JOIN source_record r ON r.space_id = s.id AND r.id = ? AND r.lifecycle = 'ACTIVE' AND viewer.id IS NOT NULL
        LEFT JOIN analysis_run a ON a.space_id = s.id AND a.record_id = r.id AND a.id = ?
        WHERE s.id = ? AND s.lifecycle = 'ACTIVE'
        """,
            (rs, n) -> {
              AnalysisDetail detail = null;
              if (rs.getObject("id") != null) {
                String failure = rs.getString("failure_code");
                Timestamp start = rs.getTimestamp("started_at");
                Timestamp end = rs.getTimestamp("finished_at");
                detail =
                    new AnalysisDetail(
                        rs.getObject("id", UUID.class),
                        rs.getObject("record_id", UUID.class),
                        AnalysisStatus.valueOf(rs.getString("status")),
                        Arrays.asList((UUID[]) rs.getArray("proposal_ids").getArray()),
                        failure == null
                            ? null
                            : new AnalysisDetail.Failure(
                                failure, AnalysisFailureCode.valueOf(failure).message()),
                        rs.getTimestamp("created_at").toInstant(),
                        start == null ? null : start.toInstant(),
                        end == null ? null : end.toInstant(),
                        rs.getBoolean("can_retry"));
              }
              return new Result(rs.getObject("viewer_id") != null, detail);
            },
            user,
            record,
            analysis,
            space)
        .stream()
        .findFirst();
  }

  public record Result(boolean authorized, AnalysisDetail detail) {}
}
