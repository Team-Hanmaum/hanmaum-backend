package com.hanmaum.backend.dashboard.repository;

import com.hanmaum.backend.dashboard.dto.DashboardResponse.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardHistoryRepository {
  private final JdbcTemplate jdbc;

  public DashboardHistoryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public RecentChanges recentChanges(UUID space) {
    return jdbc.query(
        """
        SELECT h.item_id,i.item_type,h.version,h.operation,h.changed_at,h.changed_by_membership_id,
               u.display_name,d.body->>'title' AS title,count(*) OVER () AS total
        FROM care_item_history h
        JOIN care_item i ON i.space_id=h.space_id AND i.id=h.item_id
        JOIN content_document d ON d.space_id=h.space_id AND d.id=h.document_id
        JOIN space_membership m ON m.space_id=h.space_id AND m.id=h.changed_by_membership_id
        LEFT JOIN app_user u ON u.id=m.user_id
        WHERE h.space_id=? AND i.visibility='VISIBLE' AND NOT i.needs_reconfirmation
          AND care_document_available(i.current_document_id) AND care_document_available(h.document_id)
        ORDER BY h.changed_at DESC,h.item_id ASC,h.version DESC LIMIT 3
        """,
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
        },
        space);
  }
}
