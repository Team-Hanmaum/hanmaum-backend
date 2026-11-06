package com.hanmaum.backend.record.repository;

import com.hanmaum.backend.record.repository.SourceRecordDeletionImpactRepository.ImpactRow;
import com.hanmaum.backend.record.repository.SourceRecordDeletionImpactRepository.Kind;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class SourceRecordDeletionRepository {
  private final NamedParameterJdbcTemplate jdbc;

  public SourceRecordDeletionRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The caller holds the space lock from before calculating this exact impact. */
  public void erase(
      UUID space,
      UUID record,
      long version,
      Instant now,
      List<ImpactRow> rows,
      List<UUID> items,
      List<UUID> reconfirmationItems) {
    int erased =
        jdbc.update(
            """
        UPDATE source_record SET body=NULL,lifecycle='ERASED',erased_at=:now,version=version+1
        WHERE space_id=:space AND id=:record AND lifecycle='ACTIVE' AND version=:version
        """,
            parameters(space, now).addValue("record", record).addValue("version", version));
    if (erased != 1)
      throw new IllegalStateException("Locked source record changed during deletion");

    jdbc.update(
        """
        UPDATE content_document SET body=NULL,redacted_at=:now
        WHERE space_id=:space AND id=ANY(:ids) AND redacted_at IS NULL
        """,
        parameters(space, now).addValue("ids", ids(rows, Kind.DOCUMENT)));
    jdbc.update(
        """
        UPDATE evidence SET quote=NULL,redacted_at=:now
        WHERE space_id=:space AND id=ANY(:ids) AND redacted_at IS NULL
        """,
        parameters(space, now).addValue("ids", ids(rows, Kind.EVIDENCE)));
    jdbc.update(
        """
        UPDATE ai_proposal SET invalidated_at=:now
        WHERE space_id=:space AND id=ANY(:ids) AND invalidated_at IS NULL
        """,
        parameters(space, now).addValue("ids", ids(rows, Kind.PROPOSAL)));
    jdbc.update(
        """
        UPDATE analysis_run SET status='DISCARDED',failure_code=NULL,worker_token=NULL,lease_until=NULL,
          finished_at=COALESCE(finished_at,:now),invalidated_at=:now
        WHERE space_id=:space AND id=ANY(:ids) AND invalidated_at IS NULL
        """,
        parameters(space, now).addValue("ids", ids(rows, Kind.RUN)));
    jdbc.update(
        """
        DELETE FROM care_item_basis
        WHERE space_id=:space AND id=ANY(:ids)
        """,
        parameters(space, now)
            .addValue(
                "ids",
                new SqlArrayValue(
                    "uuid",
                    rows.stream()
                        .filter(row -> row.kind() == Kind.BASIS && !row.independent())
                        .map(ImpactRow::id)
                        .toArray())));
    jdbc.update(
        """
        WITH updated AS (
          UPDATE care_item SET version=version+1,updated_at=:now,
            needs_reconfirmation=needs_reconfirmation OR id=ANY(:reconfirmationItems)
          WHERE space_id=:space AND id=ANY(:items)
          RETURNING id
        )
        DELETE FROM care_item_edit_lock
        WHERE space_id=:space AND item_id IN (SELECT id FROM updated)
        """,
        parameters(space, now)
            .addValue("items", new SqlArrayValue("uuid", items.toArray()))
            .addValue(
                "reconfirmationItems", new SqlArrayValue("uuid", reconfirmationItems.toArray())));
  }

  private MapSqlParameterSource parameters(UUID space, Instant now) {
    return new MapSqlParameterSource("space", space).addValue("now", Timestamp.from(now));
  }

  private SqlArrayValue ids(List<ImpactRow> rows, Kind kind) {
    return new SqlArrayValue(
        "uuid", rows.stream().filter(row -> row.kind() == kind).map(ImpactRow::id).toArray());
  }
}
