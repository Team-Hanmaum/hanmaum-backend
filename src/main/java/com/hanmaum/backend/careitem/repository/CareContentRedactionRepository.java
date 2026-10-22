package com.hanmaum.backend.careitem.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class CareContentRedactionRepository {
  private final JdbcTemplate jdbc;

  public CareContentRedactionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Called under the same space lock and transaction as erasing the source documents. */
  public void propagate(UUID space) {
    jdbc.update(
        """
        WITH RECURSIVE removed(id) AS (
          SELECT id FROM content_document WHERE space_id=? AND redacted_at IS NOT NULL
          UNION
          SELECT p.document_id FROM care_item_document_parent p JOIN removed r ON r.id=p.parent_document_id
            WHERE p.space_id=?
        ) UPDATE content_document d SET body=NULL,redacted_at=clock_timestamp()
          FROM removed r WHERE d.id=r.id AND d.redacted_at IS NULL
        """,
        space,
        space);
    jdbc.update(
        """
        UPDATE ai_proposal p SET invalidated_at=COALESCE(p.invalidated_at,clock_timestamp())
        FROM content_document d WHERE p.space_id=? AND d.id=p.output_document_id AND d.redacted_at IS NOT NULL
        """,
        space);
    jdbc.update(
        """
        UPDATE evidence e SET quote=NULL,redacted_at=COALESCE(e.redacted_at,clock_timestamp())
        FROM ai_proposal p WHERE p.space_id=? AND p.primary_evidence_id=e.id AND p.invalidated_at IS NOT NULL
        """,
        space);
    jdbc.update(
        """
        UPDATE care_item i SET needs_reconfirmation=TRUE,version=version+1,updated_at=clock_timestamp()
        WHERE i.space_id=? AND NOT i.needs_reconfirmation
          AND EXISTS (SELECT 1 FROM proposal_decision decision WHERE decision.space_id=i.space_id AND decision.item_id=i.id)
          AND (
          NOT care_document_available(i.current_document_id) OR EXISTS (
            SELECT 1 FROM care_item_basis b LEFT JOIN evidence e ON e.id=b.source_evidence_id
            LEFT JOIN content_document d ON d.id=b.direct_document_id
            WHERE b.space_id=i.space_id AND b.item_id=i.id AND
              ((b.source_evidence_id IS NOT NULL AND e.redacted_at IS NOT NULL)
               OR (b.direct_document_id IS NOT NULL AND d.redacted_at IS NOT NULL))))
        """,
        space);
    jdbc.update(
        """
        DELETE FROM care_item_edit_lock l USING care_item i
        WHERE l.space_id=? AND i.space_id=l.space_id AND i.id=l.item_id AND i.needs_reconfirmation
        """,
        space);
  }
}
