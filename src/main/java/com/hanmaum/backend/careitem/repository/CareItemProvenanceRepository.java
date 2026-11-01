package com.hanmaum.backend.careitem.repository;

import com.hanmaum.backend.careitem.dto.CareItemEvidenceResponse.Evidence;
import com.hanmaum.backend.record.dto.SourceRecordAuthor;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads retained provenance, never recreating a past link from today's item values. */
@Repository
public class CareItemProvenanceRepository {
  private final NamedParameterJdbcTemplate jdbc;

  public CareItemProvenanceRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<String> itemType(UUID space, UUID item) {
    return jdbc
        .query(
            "SELECT item_type FROM care_item WHERE space_id=:space AND id=:item",
            params(space, item),
            (rs, n) -> rs.getString(1))
        .stream()
        .findFirst();
  }

  public List<Evidence> evidence(UUID space, UUID item) {
    return jdbc.query(
        """
        SELECT COALESCE(e.id,b.id) evidence_id,
          CASE WHEN b.source_evidence_id IS NOT NULL THEN 'RECORD_QUOTE'
            WHEN EXISTS (SELECT 1 FROM care_item_document_parent parent
              WHERE parent.space_id=b.space_id AND parent.document_id=d.id) THEN 'DERIVED'
            ELSE 'DIRECT_INPUT' END kind,
          visible.available,
          CASE WHEN visible.available THEN r.id END record_id,
          CASE WHEN visible.available THEN COALESCE(e.quote,d.body::text) END text,
          CASE WHEN author.user_id IS NOT NULL THEN author.id END author_id,
          CASE WHEN b.source_evidence_id IS NOT NULL THEN r.recorded_at ELSE b.created_at END created_at
        FROM care_item_basis b
        LEFT JOIN evidence e ON e.space_id=b.space_id AND e.id=b.source_evidence_id
        LEFT JOIN source_record r ON r.space_id=e.space_id AND r.id=e.record_id
        LEFT JOIN content_document d ON d.space_id=b.space_id AND d.id=b.direct_document_id
        LEFT JOIN ai_proposal p ON p.space_id=b.space_id AND p.primary_evidence_id=e.id
        LEFT JOIN space_membership author ON author.space_id=b.space_id
          AND author.id=CASE WHEN b.source_evidence_id IS NOT NULL THEN r.author_membership_id
            ELSE b.submitted_by_membership_id END
        CROSS JOIN LATERAL (
          SELECT CASE WHEN b.source_evidence_id IS NOT NULL THEN
            e.quote IS NOT NULL AND e.redacted_at IS NULL AND r.lifecycle='ACTIVE'
            AND r.body IS NOT NULL AND r.version=e.record_version
            AND (p.id IS NULL OR care_document_available(p.output_document_id))
          ELSE d.body IS NOT NULL AND d.redacted_at IS NULL AND care_document_available(d.id)
          END IS TRUE AS available
        ) visible
        WHERE b.space_id=:space AND b.item_id=:item
        ORDER BY b.created_at ASC,b.id ASC
        """,
        params(space, item),
        (rs, n) ->
            new Evidence(
                rs.getObject("evidence_id", UUID.class),
                rs.getString("kind"),
                rs.getBoolean("available") ? "VALID" : "REDACTED",
                rs.getObject("record_id", UUID.class),
                rs.getString("text"),
                rs.getObject("author_id", UUID.class),
                rs.getTimestamp("created_at") == null
                    ? null
                    : rs.getTimestamp("created_at").toInstant(),
                List.of()));
  }

  public List<History> history(UUID space, UUID item, Long version) {
    return jdbc.query(
        """
        SELECT h.version,h.operation,h.status,h.changed_at,h.changed_by_membership_id,
          author.user_id, u.display_name,
          previous.version previous_version,previous.status previous_status,
          CASE WHEN care_document_available(d.id) THEN d.body::text END body,
          CASE WHEN care_document_available(old.id) THEN old.body::text END previous_body
        FROM care_item_history h
        LEFT JOIN LATERAL (
          SELECT v.* FROM care_item_history v
          WHERE v.space_id=h.space_id AND v.item_id=h.item_id AND v.version<h.version
          ORDER BY v.version DESC LIMIT 1
        ) previous ON TRUE
        JOIN content_document d ON d.space_id=h.space_id AND d.id=h.document_id
        LEFT JOIN content_document old ON old.space_id=previous.space_id AND old.id=previous.document_id
        LEFT JOIN space_membership author ON author.space_id=h.space_id AND author.id=h.changed_by_membership_id
        LEFT JOIN app_user u ON u.id=author.user_id
        WHERE h.space_id=:space AND h.item_id=:item
          AND (CAST(:version AS bigint) IS NULL OR h.version=:version)
        ORDER BY h.version DESC
        """,
        params(space, item).addValue("version", version),
        (rs, n) ->
            new History(
                rs.getLong("version"),
                rs.getString("operation"),
                rs.getString("status"),
                rs.getString("body"),
                rs.getObject("previous_version", Long.class),
                rs.getString("previous_status"),
                rs.getString("previous_body"),
                new SourceRecordAuthor(
                    rs.getObject("user_id") == null
                        ? null
                        : rs.getObject("changed_by_membership_id", UUID.class),
                    rs.getString("display_name"),
                    rs.getObject("user_id") == null),
                rs.getTimestamp("changed_at").toInstant()));
  }

  public List<UUID> historyEvidence(UUID space, UUID item, long version) {
    return jdbc.query(
        """
        SELECT DISTINCT COALESCE(b.source_evidence_id,b.id) id
        FROM care_item_history h
        JOIN care_item_basis b ON b.space_id=h.space_id AND b.item_id=h.item_id
        WHERE h.space_id=:space AND h.item_id=:item AND h.version=:version AND (
          (h.operation<>'LINK_EVIDENCE' AND b.direct_document_id IS NOT NULL AND (
            b.direct_document_id=h.document_id OR (
              EXISTS (SELECT 1 FROM care_item_document_parent parent WHERE parent.space_id=h.space_id
                AND parent.document_id=h.document_id AND parent.parent_document_id=b.direct_document_id)
              AND NOT EXISTS (SELECT 1 FROM care_item_history earlier WHERE earlier.space_id=h.space_id
                AND earlier.item_id=h.item_id AND earlier.version<h.version
                AND earlier.document_id=b.direct_document_id))))
          OR b.source_evidence_id IN (
            SELECT p.primary_evidence_id FROM proposal_decision decision
            JOIN ai_proposal p ON p.space_id=decision.space_id AND p.id=decision.proposal_id
            WHERE decision.space_id=h.space_id AND decision.item_id=h.item_id AND decision.item_version=h.version
            UNION
            SELECT p.primary_evidence_id FROM change_request_entry entry
            JOIN ai_proposal p ON p.space_id=entry.space_id AND p.id=entry.proposal_id
            WHERE entry.space_id=h.space_id AND entry.result_item_id=h.item_id
              AND entry.result_item_version=h.version AND entry.status='APPLIED'
          )) ORDER BY id
        """,
        params(space, item).addValue("version", version),
        (rs, n) -> rs.getObject(1, UUID.class));
  }

  private MapSqlParameterSource params(UUID space, UUID item) {
    return new MapSqlParameterSource("space", space).addValue("item", item);
  }

  public record History(
      long version,
      String operation,
      String status,
      String body,
      Long previousVersion,
      String previousStatus,
      String previousBody,
      SourceRecordAuthor author,
      Instant changedAt) {
    @Override
    public String toString() {
      return "CareItemHistory[version=" + version + "]";
    }
  }
}
