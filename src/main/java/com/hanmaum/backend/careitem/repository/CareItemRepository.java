package com.hanmaum.backend.careitem.repository;

import com.hanmaum.backend.careitem.dto.CreateCareItemResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class CareItemRepository {
  private final JdbcTemplate jdbc;

  public CareItemRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(
      UUID item,
      UUID space,
      UUID member,
      UUID actor,
      UUID request,
      String body,
      String type,
      String status,
      Instant now) {
    UUID document = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO content_document (id, space_id, kind, body, created_at)
        VALUES (?, ?, 'DIRECT_INPUT', CAST(? AS jsonb), ?)
        """,
        document,
        space,
        body,
        Timestamp.from(now));
    int count =
        jdbc.update(
            """
        INSERT INTO care_item (id, space_id, item_type, version, current_document_id,
          status, created_by_membership_id, created_at, updated_at, create_command_id)
        SELECT ?, ?, ?, 1, ?, ?, ?, ?, ?, id FROM command_receipt
        WHERE actor_user_id = ? AND client_request_id = ? AND command_kind = 'CREATE_CARE_ITEM'
        """,
            item,
            space,
            type,
            document,
            status,
            member,
            Timestamp.from(now),
            Timestamp.from(now),
            actor,
            request);
    if (count != 1)
      throw new IllegalStateException("Care item requires its successful command receipt");
    jdbc.update(
        """
        INSERT INTO care_item_basis (id, space_id, item_id, direct_document_id,
          submitted_by_membership_id, created_at) VALUES (?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        space,
        item,
        document,
        member,
        Timestamp.from(now));
    jdbc.update(
        """
        INSERT INTO care_item_history (space_id, item_id, version, operation, document_id,
          status, changed_by_membership_id, changed_at) VALUES (?, ?, 1, 'CREATE', ?, ?, ?, ?)
        """,
        space,
        item,
        document,
        status,
        member,
        Timestamp.from(now));
  }

  public Optional<CreateCareItemResponse> replay(UUID space, UUID item) {
    return jdbc
        .query(
            """
        SELECT i.id, i.item_type, i.created_at FROM care_item i
        JOIN content_document d ON d.space_id = i.space_id AND d.id = i.current_document_id
        WHERE i.space_id = ? AND i.id = ? AND i.visibility = 'VISIBLE'
          AND NOT i.needs_reconfirmation AND d.body IS NOT NULL AND d.redacted_at IS NULL
        """,
            (rs, n) ->
                new CreateCareItemResponse(
                    rs.getObject("id", UUID.class),
                    rs.getString("item_type"),
                    "1",
                    rs.getTimestamp("created_at").toInstant()),
            space,
            item)
        .stream()
        .findFirst();
  }
}
