package com.hanmaum.backend.careitem.repository;

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
public class EditLockRepository {
  private final JdbcTemplate jdbc;

  public EditLockRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void releaseMembership(UUID space, UUID member) {
    jdbc.update(
        "DELETE FROM care_item_edit_lock WHERE space_id=? AND holder_membership_id=?",
        space,
        member);
  }

  public Optional<Item> lockItem(UUID space, UUID item) {
    return jdbc
        .query(
            """
        SELECT i.version,i.visibility,i.needs_reconfirmation,care_document_available(i.current_document_id) available
        FROM care_item i WHERE i.space_id=? AND i.id=? FOR UPDATE
        """,
            (rs, n) ->
                new Item(
                    rs.getLong("version"),
                    "VISIBLE".equals(rs.getString("visibility"))
                        && !rs.getBoolean("needs_reconfirmation")
                        && rs.getBoolean("available")),
            space,
            item)
        .stream()
        .findFirst();
  }

  public Optional<Lease> live(UUID space, UUID item, Instant now) {
    return jdbc
        .query(
            """
        SELECT l.* FROM care_item_edit_lock l
        JOIN space_membership m ON m.space_id=l.space_id AND m.id=l.holder_membership_id AND m.is_active
        WHERE l.space_id=? AND l.item_id=? AND l.expires_at>?
        """,
            (rs, n) ->
                new Lease(
                    rs.getObject("holder_membership_id", UUID.class),
                    rs.getString("token_digest"),
                    rs.getTimestamp("expires_at").toInstant()),
            space,
            item,
            Timestamp.from(now))
        .stream()
        .findFirst();
  }

  public void save(UUID space, UUID item, UUID member, String digest, Instant expiry) {
    jdbc.update(
        """
        INSERT INTO care_item_edit_lock (space_id,item_id,holder_membership_id,token_digest,expires_at)
        VALUES (?,?,?,?,?) ON CONFLICT (item_id) DO UPDATE SET holder_membership_id=EXCLUDED.holder_membership_id,
          token_digest=EXCLUDED.token_digest,expires_at=EXCLUDED.expires_at
        """,
        space,
        item,
        member,
        digest,
        Timestamp.from(expiry));
  }

  public void release(UUID space, UUID item) {
    jdbc.update("DELETE FROM care_item_edit_lock WHERE space_id=? AND item_id=?", space, item);
  }

  public record Item(long version, boolean available) {}

  public record Lease(UUID member, String digest, Instant expiresAt) {}
}
