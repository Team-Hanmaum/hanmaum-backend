package com.hanmaum.backend.careitem.repository;

import com.hanmaum.backend.careitem.dto.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Read inside the transaction that checked access. Both home and tab use the same filter/order. */
@Repository
public class CareItemBrowseRepository {
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private static final String SOURCE =
      """
      WITH source AS (
        SELECT i.id,i.space_id,i.item_type,i.status,i.version,i.created_at,i.needs_conflict_review,
               i.needs_reconfirmation OR NOT care_document_available(i.current_document_id) AS recheck,
               d.body
        FROM care_item i JOIN content_document d ON d.space_id=i.space_id AND d.id=i.current_document_id
        WHERE i.space_id=:space AND i.visibility='VISIBLE'
          AND (CAST(:type AS text) IS NULL OR i.item_type=:type)
          AND (CAST(:status AS text) IS NULL OR i.status=:status)
      ), keys AS (
        SELECT source.*,
          body->'fields'->>(CASE item_type WHEN 'SCHEDULE' THEN 'scheduledDate'
                WHEN 'TASK' THEN 'dueDate' WHEN 'OBSERVATION' THEN 'observedDate'
                WHEN 'GUIDANCE' THEN 'receivedDate' END) AS date_text,
          body #>> '{fields,scheduledTime}' AS time_text
        FROM source
      ), rows AS (
        SELECT keys.*,
          CASE WHEN date_text ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' AND pg_input_is_valid(date_text,'date')
               THEN date_text::date END AS sort_date,
          CASE WHEN time_text ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$' THEN time_text::time END AS sort_time
        FROM keys
      )
      """;

  // Time-less appointments remain upcoming throughout their Seoul calendar date.
  private static final String PAST =
      """
      (sort_date IS NOT NULL AND CASE WHEN sort_time IS NULL
         THEN sort_date < CAST(:localNow AS timestamp)::date
         ELSE sort_date + sort_time < CAST(:localNow AS timestamp) END)
      """;
  private static final String PROJECTION =
      """
      SELECT r.id,r.item_type,r.status,r.version,r.recheck,r.needs_conflict_review,
             CASE WHEN NOT r.recheck THEN r.body->>'title' END AS title,
             CASE WHEN NOT r.recheck THEN r.body->'fields' END AS fields,
             m.id AS assignee,u.display_name,count(*) OVER () AS total
      FROM rows r
      LEFT JOIN space_membership m ON NOT r.recheck AND r.item_type='TASK'
        AND m.space_id=r.space_id AND m.is_active AND m.id=CASE
        WHEN (r.body #>> '{fields,assigneeMembershipId}') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        THEN (r.body #>> '{fields,assigneeMembershipId}')::uuid END
      LEFT JOIN app_user u ON u.id=m.user_id
      """;
  // Content edits and LINK_EVIDENCE can append history without changing the status.
  private static final String LAST_TRANSITION =
      """
      (SELECT trans.changed_at FROM (
         SELECT h.version,h.status,h.changed_at,lag(h.status) OVER (ORDER BY h.version) AS previous_status
         FROM care_item_history h WHERE h.item_id=r.id AND h.space_id=r.space_id
       ) trans WHERE trans.status=r.status AND trans.status IS DISTINCT FROM trans.previous_status
       ORDER BY trans.version DESC LIMIT 1)
      """;
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public CareItemBrowseRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public CareItemSection section(
      UUID space, String type, String status, CareItemView view, Instant asOf, Integer limit) {
    String sql =
        SOURCE
            + PROJECTION
            + " WHERE NOT r.recheck AND "
            + filter(view)
            + " ORDER BY "
            + order(type, status, view)
            + " LIMIT :limit";
    return query(
        sql, params(space, type, status, asOf).addValue("limit", limit, java.sql.Types.INTEGER));
  }

  public long pastCount(UUID space, Instant asOf) {
    return jdbc.queryForObject(
        SOURCE + "SELECT count(*) FROM rows WHERE NOT recheck AND " + filter(CareItemView.PAST),
        params(space, "SCHEDULE", null, asOf),
        Long.class);
  }

  public List<CareItemSummary> reconfirmations(
      UUID space, String type, String status, Instant asOf) {
    return query(
            SOURCE + PROJECTION + " WHERE r.recheck ORDER BY r.created_at DESC,r.id DESC",
            params(space, type, status, asOf))
        .items();
  }

  private MapSqlParameterSource params(UUID space, String type, String status, Instant asOf) {
    return new MapSqlParameterSource("space", space)
        .addValue("type", type)
        .addValue("status", status)
        .addValue("localNow", LocalDateTime.ofInstant(asOf, SEOUL));
  }

  private String filter(CareItemView view) {
    if (view == null) return "TRUE";
    return switch (view) {
      case UPCOMING -> "status='SCHEDULED' AND NOT " + PAST;
      case PAST -> "status='SCHEDULED' AND " + PAST;
      case CLOSED -> "status IN ('COMPLETED','CANCELED')";
      case REMAINING -> "status IN ('NOT_STARTED','IN_PROGRESS')";
      case COMPLETED -> "status='COMPLETED'";
      case UNCONFIRMED -> "status='UNCONFIRMED'";
      case CONFIRMED -> "status='CONFIRMED'";
    };
  }

  private String order(String type, String status, CareItemView view) {
    String tie = "r.created_at ASC,r.id ASC";
    if (view == null) {
      if ("OBSERVATION".equals(type) || "GUIDANCE".equals(type))
        return "sort_date DESC NULLS LAST," + tie;
      if ("COMPLETED".equals(status) || "CANCELED".equals(status) || "CONFIRMED".equals(status))
        return LAST_TRANSITION + " DESC NULLS LAST," + tie;
      if ("UNCONFIRMED".equals(status)) return tie;
      if ("TASK".equals(type) && status != null) return "sort_date ASC NULLS LAST," + tie;
      return "r.created_at DESC,r.id DESC";
    }
    return switch (view) {
      case UPCOMING ->
          "sort_date ASC NULLS LAST,CASE WHEN sort_date IS NOT NULL THEN sort_time END ASC NULLS LAST,"
              + tie;
      case PAST -> "sort_date DESC NULLS LAST,sort_time DESC NULLS LAST," + tie;
      case REMAINING -> "sort_date ASC NULLS LAST,(r.status='IN_PROGRESS') DESC," + tie;
      case CLOSED, COMPLETED, CONFIRMED -> LAST_TRANSITION + " DESC NULLS LAST," + tie;
      case UNCONFIRMED -> tie;
    };
  }

  private CareItemSection query(String sql, MapSqlParameterSource params) {
    return jdbc.query(
        sql,
        params,
        rs -> {
          List<CareItemSummary> items = new ArrayList<>();
          long total = 0;
          while (rs.next()) {
            total = rs.getLong("total");
            items.add(summary(rs));
          }
          return new CareItemSection(items, total);
        });
  }

  private CareItemSummary summary(ResultSet rs) throws SQLException {
    String type = rs.getString("item_type"), json = rs.getString("fields");
    var fields = json == null ? mapper.createObjectNode() : mapper.readTree(json);
    if (!fields.isObject()) fields = mapper.createObjectNode();
    if ("TASK".equals(type) && !rs.getBoolean("recheck"))
      ((ObjectNode) fields).put("assigneeMembershipId", rs.getString("assignee"));
    return new CareItemSummary(
        rs.getObject("id", UUID.class),
        type,
        rs.getString("version"),
        rs.getString("title"),
        rs.getString("status"),
        rs.getBoolean("recheck"),
        rs.getBoolean("needs_conflict_review"),
        fields,
        rs.getString("display_name"));
  }
}
