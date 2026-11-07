package com.hanmaum.backend.sharing.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class ShareLinkRepository {
  private final JdbcTemplate jdbc;

  public ShareLinkRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts metadata only under the caller's existing space write lock. */
  public void insert(Link link, List<Target> targets) {
    jdbc.update(
        "INSERT INTO share_link(id,space_id,issued_by_membership_id,token_hash,url_prefix,created_at) VALUES (?,?,?,?,?,?)",
        link.id(),
        link.space(),
        link.issuer(),
        link.hash(),
        link.prefix(),
        Timestamp.from(link.createdAt()));
    for (int i = 0; i < targets.size(); i++) {
      var target = targets.get(i);
      jdbc.update(
          "INSERT INTO share_link_target(space_id,share_link_id,ordinal_no,item_id,item_type,shared_item_version,includes_change) VALUES (?,?,?,?,?,?,?)",
          link.space(),
          link.id(),
          i,
          target.item(),
          target.type(),
          target.version(),
          target.includesChange());
    }
  }

  public Optional<Link> byHash(String hash) {
    return find("token_hash", hash);
  }

  public Optional<Link> byId(UUID id) {
    return find("id", id);
  }

  /** The column is selected by internal methods, never from request input. */
  private Optional<Link> find(String column, Object value) {
    return jdbc
        .query(
            "SELECT id,space_id,issued_by_membership_id,token_hash,url_prefix,created_at FROM share_link WHERE "
                + column
                + "=?",
            (rs, n) ->
                new Link(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getTimestamp(6).toInstant()),
            value)
        .stream()
        .findFirst();
  }

  /** Preserves the user's selection order while exposing no saved care text. */
  public List<Target> targets(UUID id) {
    return jdbc.query(
        "SELECT item_id,item_type,shared_item_version,includes_change FROM share_link_target WHERE share_link_id=? ORDER BY ordinal_no",
        (rs, n) ->
            new Target(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getBoolean(4)),
        id);
  }

  /** A change badge requires an actual, still-readable confirmed revision beyond creation. */
  public Set<UUID> validChanges(UUID space, List<UUID> ids) {
    return new HashSet<>(
        jdbc.query(
            """
        SELECT DISTINCT h.item_id FROM care_item_history h JOIN care_item i ON i.space_id=h.space_id AND i.id=h.item_id
        WHERE h.space_id=? AND h.item_id=ANY(CAST(? AS uuid[])) AND h.version>1 AND h.version<=i.version
          AND h.operation IN ('UPDATE','RESOLVE','LINK_EVIDENCE','UNASSIGN','RECONFIRM')
          AND care_document_available(h.document_id)
        """,
            (rs, n) -> rs.getObject(1, UUID.class),
            space,
            new SqlArrayValue("uuid", ids.toArray())));
  }

  public record Link(
      UUID id, UUID space, UUID issuer, String hash, String prefix, Instant createdAt) {}

  public record Target(UUID item, String type, long version, boolean includesChange) {}
}
