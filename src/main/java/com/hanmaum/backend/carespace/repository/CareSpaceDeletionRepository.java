package com.hanmaum.backend.carespace.repository;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class CareSpaceDeletionRepository {
  // Closed internal table names in child-before-parent deletion order. All space-owned rows,
  // including erased/invalid/ended rows, participate. Global users, sessions and receipts do not.
  private static final List<String> TABLES =
      List.of(
          "share_link_target",
          "share_link",
          "change_request_entry",
          "change_request_decision_batch",
          "change_request",
          "proposal_decision",
          "proposal_decision_batch",
          "analysis_input_basis",
          "analysis_input_item",
          "analysis_input_source",
          "care_item_edit_lock",
          "care_item_basis",
          "care_item_history",
          "care_item_document_parent",
          "care_item_document_evidence",
          "ai_proposal",
          "analysis_run",
          "care_item",
          "evidence",
          "content_document",
          "source_record",
          "invitation",
          "space_membership",
          "care_space");
  private final JdbcTemplate jdbc;

  public CareSpaceDeletionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Metadata-only graph. Immutable content changes create new document IDs; erasure changes state.
   */
  public List<TableSnapshot> snapshot(UUID space) {
    var result = new ArrayList<TableSnapshot>();
    for (String table : TABLES) {
      // Renewal deadlines are execution mechanics, not a changed deletion scope. Stored bearer
      // material and all care text are excluded before leaving PostgreSQL.
      String sql =
          "SELECT state FROM (SELECT (to_jsonb(t) - ARRAY['body','quote','subject_label',"
              + "'encrypted_url','url_prefix','token_hash','token_digest','worker_token','lease_until',"
              + "'expires_at'])::text AS state FROM "
              + table
              + " t WHERE "
              + scope(table)
              + "=?) graph ORDER BY state COLLATE \"C\"";
      result.add(new TableSnapshot(table, jdbc.queryForList(sql, String.class, space)));
    }
    return List.copyOf(result);
  }

  /** Caller holds the current owner's space lock; every removal and receipt commits atomically. */
  public void purge(UUID space) {
    int changed =
        jdbc.update(
            "UPDATE care_space SET lifecycle='DELETING' WHERE id=? AND lifecycle='ACTIVE'", space);
    if (changed != 1) throw new IllegalStateException("Locked care space changed during deletion");
    // Break only the invitation/participation cycle. The active-owner FK is already deferred and
    // both of its rows are removed in this transaction; no global constraint disabling is needed.
    jdbc.update("UPDATE space_membership SET invitation_id=NULL WHERE space_id=?", space);
    for (String table : TABLES) {
      int removed = jdbc.update("DELETE FROM " + table + " WHERE " + scope(table) + "=?", space);
      if (table.equals("care_space") && removed != 1)
        throw new IllegalStateException("Locked care space was not removed");
    }
  }

  private static String scope(String table) {
    return table.equals("care_space") ? "id" : "space_id";
  }

  public record TableSnapshot(String table, List<String> states) {}
}
