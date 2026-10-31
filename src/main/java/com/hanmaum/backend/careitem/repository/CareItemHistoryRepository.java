package com.hanmaum.backend.careitem.repository;

import com.hanmaum.backend.careitem.dto.RecentChanges;
import com.hanmaum.backend.careitem.dto.RecentChanges.Change;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CareItemHistoryRepository {
  private final NamedParameterJdbcTemplate jdbc;

  public CareItemHistoryRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public RecentChanges recentChanges(UUID space) {
    // Propagate unavailable roots once for the space. A blocked current document hides
    // the entire item; a blocked historical document hides just the affected versions.
    return jdbc.query(
        """
        WITH RECURSIVE blocked(id) AS (
            SELECT id FROM care_document_local_visibility WHERE space_id=:space AND available IS NOT TRUE
            UNION
            SELECT p.document_id FROM care_item_document_parent p
            JOIN blocked b ON b.id=p.parent_document_id WHERE p.space_id=:space
        ), eligible AS NOT MATERIALIZED (
            SELECT h.*,i.item_type FROM care_item_history h
            JOIN care_item i ON i.space_id=h.space_id AND i.id=h.item_id
            WHERE h.space_id=:space AND i.visibility='VISIBLE' AND NOT i.needs_reconfirmation
              AND NOT EXISTS (SELECT 1 FROM blocked WHERE id=i.current_document_id)
              AND NOT EXISTS (SELECT 1 FROM blocked WHERE id=h.document_id)
        ), preview AS (
            SELECT * FROM eligible ORDER BY changed_at DESC,item_id ASC,version DESC LIMIT 3
        )
        SELECT p.item_id,p.item_type,p.version,p.operation,p.changed_at,p.changed_by_membership_id,
               u.display_name,d.body->>'title' AS title,(SELECT count(*) FROM eligible) AS total
        FROM preview p
        JOIN content_document d ON d.id=p.document_id
        JOIN space_membership m ON m.id=p.changed_by_membership_id
        LEFT JOIN app_user u ON u.id=m.user_id
        ORDER BY p.changed_at DESC,p.item_id ASC,p.version DESC
        """,
        new MapSqlParameterSource("space", space),
        rs -> {
          List<Change> items = new ArrayList<>();
          long total = 0;
          while (rs.next()) {
            total = rs.getLong("total");
            items.add(
                new Change(
                    rs.getObject("item_id", UUID.class),
                    rs.getString("item_type"),
                    rs.getString("version"),
                    rs.getString("operation"),
                    rs.getTimestamp("changed_at").toInstant(),
                    rs.getObject("changed_by_membership_id", UUID.class),
                    rs.getString("display_name"),
                    rs.getString("title")));
          }
          return new RecentChanges(items, total);
        });
  }
}
