package com.hanmaum.backend.analysis.repository;

import com.hanmaum.backend.analysis.code.AnalysisFailureCode;
import com.hanmaum.backend.analysis.entity.AnalysisRun;
import com.hanmaum.backend.analysis.entity.AnalysisStatus;
import com.hanmaum.backend.record.entity.SourceRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnalysisRepository {
  private final JdbcTemplate jdbc;

  public AnalysisRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public UUID enqueue(
      SourceRecord source,
      UUID membership,
      AnalysisRun failed,
      UUID actor,
      UUID command,
      Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO analysis_run (id, space_id, record_id, record_version, attempt_no,
          request_id, input_recorded_at, timezone, requested_by_membership_id, status,
          created_at, retry_of_id, command_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'Asia/Seoul', ?, 'QUEUED', ?, ?,
          (SELECT id FROM command_receipt WHERE actor_user_id = ? AND client_request_id = ?))
        """,
        id,
        source.spaceId(),
        source.id(),
        source.version(),
        failed == null ? 1L : Math.addExact(failed.attemptNo(), 1),
        UUID.randomUUID(),
        Timestamp.from(source.recordedAt()),
        membership,
        Timestamp.from(now),
        failed == null ? null : failed.id(),
        actor,
        command);
    jdbc.update(
        "INSERT INTO analysis_input_source VALUES (?, ?, ?, ?)",
        source.spaceId(),
        id,
        source.id(),
        source.version());
    return id;
  }

  public void attachCommand(UUID id, UUID actor, UUID command) {
    jdbc.update(
        """
        UPDATE analysis_run SET command_id =
          (SELECT id FROM command_receipt WHERE actor_user_id = ? AND client_request_id = ?)
        WHERE id = ?
        """,
        actor,
        command,
        id);
  }

  public Optional<AnalysisRun> find(UUID space, UUID record, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM analysis_run WHERE space_id = ? AND record_id = ? AND id = ?",
            this::map,
            space,
            record,
            id)
        .stream()
        .findFirst();
  }

  public Optional<AnalysisRun> latest(UUID space, UUID record) {
    return jdbc
        .query(
            "SELECT * FROM analysis_run WHERE space_id = ? AND record_id = ? ORDER BY attempt_no DESC LIMIT 1",
            this::map,
            space,
            record)
        .stream()
        .findFirst();
  }

  public Optional<AnalysisRun> initial(UUID space, UUID record) {
    return jdbc
        .query(
            "SELECT * FROM analysis_run WHERE space_id = ? AND record_id = ? AND retry_of_id IS NULL",
            this::map,
            space,
            record)
        .stream()
        .findFirst();
  }

  public List<AnalysisRun> queued(int limit) {
    return jdbc.query(
        "SELECT * FROM analysis_run WHERE status = 'QUEUED' ORDER BY created_at, id LIMIT ?",
        this::map,
        limit);
  }

  public List<AnalysisRun> expired(int limit) {
    return jdbc.query(
        "SELECT * FROM analysis_run WHERE status = 'RUNNING' AND lease_until <= clock_timestamp() ORDER BY lease_until LIMIT ?",
        this::map,
        limit);
  }

  public boolean lockSpace(UUID space) {
    return jdbc
        .query(
            "SELECT lifecycle FROM care_space WHERE id = ? FOR UPDATE",
            (rs, n) -> rs.getString(1),
            space)
        .stream()
        .anyMatch("ACTIVE"::equals);
  }

  public Optional<AnalysisRun> lockRun(UUID id) {
    return jdbc.query("SELECT * FROM analysis_run WHERE id = ? FOR UPDATE", this::map, id).stream()
        .findFirst();
  }

  public boolean validInputs(UUID runId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS (SELECT 1 FROM analysis_input_source WHERE run_id = ?)
          AND NOT EXISTS (
            SELECT 1 FROM analysis_input_source i
            LEFT JOIN source_record r ON r.space_id = i.space_id AND r.id = i.source_record_id
            WHERE i.run_id = ? AND (r.id IS NULL OR r.lifecycle <> 'ACTIVE' OR r.version <> i.source_version))
          AND NOT EXISTS (
            SELECT 1 FROM analysis_input_item dep
            LEFT JOIN care_item item ON item.space_id=dep.space_id AND item.id=dep.item_id
            LEFT JOIN content_document d ON d.space_id=dep.space_id AND d.id=dep.document_id
            WHERE dep.run_id = ? AND (item.id IS NULL OR item.visibility<>'VISIBLE'
              OR item.needs_reconfirmation OR item.version<>dep.item_version
              OR item.current_document_id<>dep.document_id OR d.body IS NULL OR d.redacted_at IS NOT NULL
              OR dep.source_evidence_ids IS NULL
              OR EXISTS (
                SELECT 1 FROM unnest(dep.source_evidence_ids) captured(evidence_id)
                LEFT JOIN evidence e ON e.space_id=dep.space_id AND e.id=captured.evidence_id
                WHERE e.id IS NULL OR e.quote IS NULL OR e.redacted_at IS NOT NULL
                  OR NOT EXISTS (
                    SELECT 1 FROM analysis_input_source source
                    WHERE source.space_id=dep.space_id AND source.run_id=dep.run_id
                      AND source.source_record_id=e.record_id AND source.source_version=e.record_version))))
        """,
            Boolean.class,
            runId,
            runId,
            runId));
  }

  public boolean activeRequester(AnalysisRun run) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS (SELECT 1 FROM space_membership
        WHERE space_id = ? AND id = ? AND is_active AND user_id IS NOT NULL)
        """,
            Boolean.class,
            run.spaceId(),
            run.membershipId()));
  }

  public boolean liveLease(UUID runId, UUID token) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS (SELECT 1 FROM analysis_run WHERE id = ? AND status = 'RUNNING'
        AND worker_token = ? AND lease_until > clock_timestamp())
        """,
            Boolean.class,
            runId,
            token));
  }

  public AnalysisRun claim(UUID id, UUID token, Duration lease) {
    jdbc.update(
        """
        UPDATE analysis_run SET status = 'RUNNING', started_at = clock_timestamp(),
          worker_token = ?, lease_until = clock_timestamp() + (? * INTERVAL '1 millisecond')
        WHERE id = ? AND status = 'QUEUED'
        """,
        token,
        lease.toMillis(),
        id);
    return lockRun(id).orElseThrow();
  }

  public boolean heartbeat(UUID id, UUID token, Duration lease) {
    return jdbc.update(
            """
        UPDATE analysis_run SET lease_until = clock_timestamp() + (? * INTERVAL '1 millisecond')
        WHERE id = ? AND status = 'RUNNING' AND worker_token = ? AND lease_until > clock_timestamp()
        """,
            lease.toMillis(),
            id,
            token)
        == 1;
  }

  public void finish(AnalysisRun run, AnalysisStatus status, AnalysisFailureCode failure) {
    jdbc.update(
        """
        UPDATE analysis_run SET status = ?, failure_code = ?, finished_at = clock_timestamp(),
          worker_token = NULL, lease_until = NULL,
          invalidated_at = CASE WHEN ? = 'DISCARDED' THEN clock_timestamp() ELSE invalidated_at END
        WHERE id = ?
        """,
        status.name(),
        failure == null ? null : failure.name(),
        status.name(),
        run.id());
  }

  public List<AnalysisRun> dependingOn(UUID space, UUID record) {
    return jdbc.query(
        """
        SELECT a.* FROM analysis_run a JOIN analysis_input_source i ON i.run_id = a.id
        WHERE i.space_id = ? AND i.source_record_id = ? ORDER BY a.id
        """,
        this::map,
        space,
        record);
  }

  private AnalysisRun map(ResultSet rs, int row) throws SQLException {
    String failure = rs.getString("failure_code");
    return new AnalysisRun(
        rs.getObject("id", UUID.class),
        rs.getObject("space_id", UUID.class),
        rs.getObject("record_id", UUID.class),
        rs.getLong("record_version"),
        rs.getLong("attempt_no"),
        rs.getObject("request_id", UUID.class),
        time(rs, "input_recorded_at"),
        rs.getObject("requested_by_membership_id", UUID.class),
        AnalysisStatus.valueOf(rs.getString("status")),
        failure == null ? null : AnalysisFailureCode.valueOf(failure),
        time(rs, "created_at"),
        time(rs, "started_at"),
        time(rs, "finished_at"),
        rs.getObject("worker_token", UUID.class),
        time(rs, "lease_until"));
  }

  private Instant time(ResultSet rs, String key) throws SQLException {
    Timestamp value = rs.getTimestamp(key);
    return value == null ? null : value.toInstant();
  }
}
