package com.hanmaum.backend.careitem.service;

import com.hanmaum.backend.ai.dto.AiAnalysisRequest.CandidateItem;
import com.hanmaum.backend.ai.dto.CareItemType;
import com.hanmaum.backend.analysis.service.AnalysisCandidateService.CandidateIntegrationRequiredException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Captures the exact item/document, evidence and source dependencies in the claim transaction. */
@Service
@Transactional(
    propagation = Propagation.MANDATORY,
    noRollbackFor = CandidateIntegrationRequiredException.class)
public class CareItemCandidateService {
  private final JdbcTemplate jdbc;

  public CareItemCandidateService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<CandidateItem> capture(UUID space, UUID run) {
    var rows =
        jdbc.query(
            """
        SELECT i.id, i.version, i.item_type, i.current_document_id, d.body::text AS summary,
          ARRAY(SELECT b.source_evidence_id FROM care_item_basis b
            WHERE b.space_id=i.space_id AND b.item_id=i.id AND b.source_evidence_id IS NOT NULL
            ORDER BY b.source_evidence_id) AS source_evidence_ids
        FROM care_item i JOIN content_document d ON d.space_id = i.space_id AND d.id = i.current_document_id
        WHERE i.space_id = ? AND i.visibility = 'VISIBLE' AND NOT i.needs_reconfirmation
          AND d.body IS NOT NULL AND d.redacted_at IS NULL
          AND EXISTS (SELECT 1 FROM care_item_basis b
            LEFT JOIN content_document direct ON direct.space_id=b.space_id AND direct.id=b.direct_document_id
            LEFT JOIN evidence e ON e.space_id=b.space_id AND e.id=b.source_evidence_id
            LEFT JOIN source_record r ON r.space_id=e.space_id AND r.id=e.record_id
            WHERE b.space_id=i.space_id AND b.item_id=i.id
              AND ((direct.body IS NOT NULL AND direct.redacted_at IS NULL)
                OR (e.quote IS NOT NULL AND e.redacted_at IS NULL AND r.lifecycle='ACTIVE' AND r.version=e.record_version)))
          AND NOT EXISTS (SELECT 1 FROM care_item_basis b JOIN evidence e ON e.space_id=b.space_id AND e.id=b.source_evidence_id
            LEFT JOIN source_record r ON r.space_id=e.space_id AND r.id=e.record_id
            WHERE b.space_id=i.space_id AND b.item_id=i.id
              AND (e.redacted_at IS NOT NULL OR e.quote IS NULL OR r.id IS NULL OR r.lifecycle<>'ACTIVE' OR r.version<>e.record_version))
        ORDER BY i.created_at DESC, i.id DESC LIMIT 51
        """,
            (rs, n) ->
                new Captured(
                    rs.getObject("id", UUID.class),
                    rs.getLong("version"),
                    CareItemType.valueOf(rs.getString("item_type")),
                    rs.getObject("current_document_id", UUID.class),
                    rs.getString("summary"),
                    (UUID[]) rs.getArray("source_evidence_ids").getArray()),
            space);
    // These are existing internal transport limits, not new public input/space limits.
    if (rows.size() > 50 || rows.stream().anyMatch(r -> r.summary().length() > 2000))
      throw new CandidateIntegrationRequiredException();
    for (var row : rows) {
      jdbc.update(
          connection -> {
            var statement =
                connection.prepareStatement(
                    """
                INSERT INTO analysis_input_item
                  (space_id, run_id, item_id, item_version, document_id, source_evidence_ids)
                VALUES (?, ?, ?, ?, ?, ?)
                """);
            statement.setObject(1, space);
            statement.setObject(2, run);
            statement.setObject(3, row.id());
            statement.setLong(4, row.version());
            statement.setObject(5, row.document());
            statement.setArray(6, connection.createArrayOf("uuid", row.evidenceIds()));
            return statement;
          });
      jdbc.update(
          """
          INSERT INTO analysis_input_source (space_id, run_id, source_record_id, source_version)
          SELECT b.space_id, ?, e.record_id, e.record_version FROM care_item_basis b
          JOIN evidence e ON e.space_id=b.space_id AND e.id=b.source_evidence_id
          WHERE b.space_id=? AND b.item_id=? ON CONFLICT (run_id, source_record_id) DO NOTHING
          """,
          run,
          space,
          row.id());
    }
    return rows.stream()
        .map(r -> new CandidateItem(r.id(), r.version(), r.type(), r.summary()))
        .toList();
  }

  private record Captured(
      UUID id,
      long version,
      CareItemType type,
      UUID document,
      String summary,
      UUID[] evidenceIds) {}
}
