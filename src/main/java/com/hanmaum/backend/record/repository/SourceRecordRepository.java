package com.hanmaum.backend.record.repository;

import com.hanmaum.backend.record.entity.SourceRecord;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class SourceRecordRepository {
  private final JdbcTemplate jdbc;

  public SourceRecordRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(SourceRecord record, UUID actorId, UUID requestId) {
    int count =
        jdbc.update(
            """
        INSERT INTO source_record (id, space_id, author_membership_id, body, recorded_at, version, create_command_id)
        SELECT ?, ?, ?, ?, ?, ?, id FROM command_receipt
        WHERE actor_user_id = ? AND client_request_id = ? AND command_kind = 'CREATE_SOURCE_RECORD'
        """,
            record.id(),
            record.spaceId(),
            record.authorMembershipId(),
            record.body(),
            Timestamp.from(record.recordedAt()),
            record.version(),
            actorId,
            requestId);
    if (count != 1)
      throw new IllegalStateException("Source record requires its successful command receipt");
  }

  public Optional<SourceRecord> findActive(UUID spaceId, UUID recordId) {
    return jdbc
        .query(
            """
        SELECT id, space_id, author_membership_id, body, recorded_at, version
        FROM source_record WHERE space_id = ? AND id = ? AND lifecycle = 'ACTIVE'
        """,
            (rs, row) ->
                new SourceRecord(
                    rs.getObject("id", UUID.class),
                    rs.getObject("space_id", UUID.class),
                    rs.getObject("author_membership_id", UUID.class),
                    rs.getString("body"),
                    rs.getTimestamp("recorded_at").toInstant(),
                    rs.getLong("version")),
            spaceId,
            recordId)
        .stream()
        .findFirst();
  }
}
