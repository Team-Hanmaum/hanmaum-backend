package com.hanmaum.backend.careitem.repository;

import com.hanmaum.backend.careitem.dto.CareItemDetail;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CareItemQueryRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public CareItemQueryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public Optional<Result> read(UUID actor, UUID space, UUID item, String type, String status) {
    // One statement is both the authorization gate and the data snapshot, also for an empty list.
    var rows =
        jdbc.query(
            """
        SELECT viewer.id AS viewer_id, i.*, CASE WHEN care_document_available(d.id) THEN d.body::text END AS document_body,
          d.redacted_at AS document_redacted, l.holder_membership_id,l.expires_at,
          l.holder_membership_id=viewer.id AS own_lock,assignee.id AS active_assignee
        FROM care_space s
        LEFT JOIN space_membership viewer ON viewer.space_id = s.id AND viewer.user_id = ? AND viewer.is_active
        LEFT JOIN care_item i ON i.space_id = s.id AND viewer.id IS NOT NULL
          AND ((CAST(? AS uuid) IS NOT NULL AND i.id = ?) OR (CAST(? AS uuid) IS NULL AND i.visibility = 'VISIBLE'))
          AND (CAST(? AS text) IS NULL OR i.item_type = ?) AND (CAST(? AS text) IS NULL OR i.status = ?)
        LEFT JOIN content_document d ON d.space_id = s.id AND d.id = i.current_document_id
        LEFT JOIN care_item_edit_lock l ON l.space_id=i.space_id AND l.item_id=i.id AND l.expires_at>statement_timestamp()
          AND i.visibility='VISIBLE' AND NOT i.needs_reconfirmation AND care_document_available(d.id)
          AND EXISTS(SELECT 1 FROM space_membership m WHERE m.space_id=l.space_id AND m.id=l.holder_membership_id AND m.is_active)
        LEFT JOIN space_membership assignee ON assignee.space_id=i.space_id AND assignee.is_active
          AND assignee.id=CASE WHEN (d.body #>> '{fields,assigneeMembershipId}') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            THEN (d.body #>> '{fields,assigneeMembershipId}')::uuid END
        WHERE s.id = ? AND s.lifecycle = 'ACTIVE'
        ORDER BY i.created_at DESC, i.id DESC
        """,
            this::map, actor, item, item, item, type, type, status, status, space);
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(
        new Result(
            rows.getFirst().authorized(),
            rows.stream().map(Row::item).filter(Objects::nonNull).toList()));
  }

  private Row map(ResultSet rs, int n) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    if (id == null) return new Row(rs.getObject("viewer_id") != null, null);
    boolean deleted = "DELETED".equals(rs.getString("visibility"));
    String body = rs.getString("document_body");
    boolean redacted = body == null || rs.getObject("document_redacted") != null;
    JsonNode values = redacted || deleted ? mapper.createObjectNode() : mapper.readTree(body);
    List<String> removed =
        new ArrayList<>(
            mapper.readValue(
                rs.getString("redacted_fields"), new TypeReference<List<String>>() {}));
    if (redacted && !deleted)
      for (String field : List.of("title", "description", "fields"))
        if (!removed.contains(field)) removed.add(field);
    JsonNode fields = values.path("fields");
    if (!fields.isObject()) fields = mapper.createObjectNode();
    if (!redacted && !deleted && "TASK".equals(rs.getString("item_type")))
      ((tools.jackson.databind.node.ObjectNode) fields)
          .put("assigneeMembershipId", rs.getString("active_assignee"));
    var detail =
        new CareItemDetail(
            id,
            rs.getObject("space_id", UUID.class),
            rs.getString("item_type"),
            Long.toString(rs.getLong("version")),
            string(values, "title"),
            string(values, "description"),
            deleted ? null : rs.getString("status"),
            fields,
            rs.getString("visibility"),
            !deleted && (redacted || rs.getBoolean("needs_reconfirmation")),
            rs.getBoolean("needs_conflict_review"),
            deleted ? List.of() : removed,
            rs.getObject("holder_membership_id") == null
                ? null
                : new com.hanmaum.backend.careitem.dto.EditLockView(
                    rs.getObject("holder_membership_id", UUID.class),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getBoolean("own_lock")),
            List.of(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            string(values, "confirmedAnswer"));
    return new Row(rs.getObject("viewer_id") != null, detail);
  }

  private String string(JsonNode node, String key) {
    var value = node.path(key);
    return value.isString() ? value.asString() : null;
  }

  private record Row(boolean authorized, CareItemDetail item) {}

  public record Result(boolean authorized, List<CareItemDetail> items) {}
}
