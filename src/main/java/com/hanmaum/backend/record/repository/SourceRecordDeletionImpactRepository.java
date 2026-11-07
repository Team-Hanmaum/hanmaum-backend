package com.hanmaum.backend.record.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the stored provenance graph without loading any source or derived payload. */
@Repository
public class SourceRecordDeletionImpactRepository {
  private final NamedParameterJdbcTemplate jdbc;

  public SourceRecordDeletionImpactRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Access> access(UUID actor, UUID space, UUID record, boolean includeErased) {
    return jdbc
        .query(
            """
        SELECT viewer.id IS NOT NULL AS participating,
               viewer.id=s.owner_membership_id OR author.user_id=viewer.user_id AS can_delete,
               r.version
        FROM care_space s
        LEFT JOIN space_membership viewer
          ON viewer.space_id=s.id AND viewer.user_id=:actor AND viewer.is_active
        LEFT JOIN source_record r
          ON r.space_id=s.id AND r.id=:record AND (r.lifecycle='ACTIVE' OR :includeErased)
            AND viewer.id IS NOT NULL
        LEFT JOIN space_membership author ON author.space_id=s.id AND author.id=r.author_membership_id
        WHERE s.id=:space AND s.lifecycle='ACTIVE'
        """,
            Map.of(
                "actor", actor, "space", space, "record", record, "includeErased", includeErased),
            (rs, row) ->
                new Access(
                    rs.getBoolean("participating"),
                    rs.getBoolean("can_delete"),
                    rs.getObject("version", Long.class)))
        .stream()
        .findFirst();
  }

  public List<ImpactRow> snapshot(UUID space, UUID record) {
    // Dependency direction matters: a linked basis can affect an item without tainting
    // its independently written document. Follow document ancestry and captured inputs only.
    return jdbc.query(
        """
        WITH RECURSIVE edges(from_kind, from_id, to_kind, to_id) AS (
          SELECT 'RECORD',record_id,'EVIDENCE',id FROM evidence WHERE space_id=:space
          UNION ALL SELECT 'RECORD',record_id,'RUN',id FROM analysis_run WHERE space_id=:space
          UNION ALL SELECT 'RECORD',source_record_id,'RUN',run_id
            FROM analysis_input_source WHERE space_id=:space
          UNION ALL SELECT 'DOCUMENT',document_id,'RUN',run_id
            FROM analysis_input_item WHERE space_id=:space
          UNION ALL SELECT 'EVIDENCE',captured.evidence_id,'RUN',i.run_id
            FROM analysis_input_item i CROSS JOIN LATERAL unnest(i.source_evidence_ids) captured(evidence_id)
            WHERE i.space_id=:space
          UNION ALL SELECT 'DOCUMENT',direct_document_id,'RUN',run_id
            FROM analysis_input_basis WHERE space_id=:space AND direct_document_id IS NOT NULL
          UNION ALL SELECT 'EVIDENCE',source_evidence_id,'RUN',run_id
            FROM analysis_input_basis WHERE space_id=:space AND source_evidence_id IS NOT NULL
          UNION ALL SELECT 'EVIDENCE',evidence_id,'DOCUMENT',document_id
            FROM care_item_document_evidence WHERE space_id=:space
          UNION ALL SELECT 'RUN',run_id,'PROPOSAL',id FROM ai_proposal WHERE space_id=:space
          UNION ALL SELECT 'EVIDENCE',primary_evidence_id,'PROPOSAL',id FROM ai_proposal WHERE space_id=:space
          UNION ALL SELECT 'DOCUMENT',output_document_id,'PROPOSAL',id FROM ai_proposal WHERE space_id=:space
          UNION ALL SELECT 'PROPOSAL',id,'EVIDENCE',primary_evidence_id FROM ai_proposal WHERE space_id=:space
          UNION ALL SELECT 'PROPOSAL',id,'DOCUMENT',output_document_id FROM ai_proposal WHERE space_id=:space
          UNION ALL SELECT 'DOCUMENT',parent_document_id,'DOCUMENT',document_id
            FROM care_item_document_parent WHERE space_id=:space
        ),
        reached(kind, id) AS (
          SELECT 'RECORD'::text, CAST(:record AS uuid)
          UNION
          SELECT edge.to_kind,edge.to_id FROM reached node
          JOIN edges edge ON edge.from_kind=node.kind AND edge.from_id=node.id
        ),
        affected_documents AS (
          SELECT d.id FROM reached node JOIN content_document d ON d.id=node.id AND d.space_id=:space
          WHERE node.kind='DOCUMENT' AND d.body IS NOT NULL
        ),
        affected_evidence AS (
          SELECT e.id,e.record_id,e.record_version FROM reached node
          JOIN evidence e ON e.id=node.id AND e.space_id=:space
          WHERE node.kind='EVIDENCE' AND e.quote IS NOT NULL
        ),
        affected_proposals AS (
          SELECT p.* FROM reached node JOIN ai_proposal p ON p.id=node.id AND p.space_id=:space
          WHERE node.kind='PROPOSAL'
            AND (p.output_document_id IN (SELECT id FROM affected_documents)
                 OR p.primary_evidence_id IN (SELECT id FROM affected_evidence))
        ),
        affected_decisions AS (
          SELECT d.item_id,d.item_version FROM proposal_decision d
          JOIN affected_proposals p ON p.id=d.proposal_id
          JOIN affected_evidence evidence ON evidence.id=p.primary_evidence_id
          JOIN care_item_basis b ON b.space_id=d.space_id AND b.item_id=d.item_id
            AND b.source_evidence_id=evidence.id
          WHERE d.space_id=:space
          UNION
          SELECT e.result_item_id,e.result_item_version FROM change_request_entry e
          JOIN affected_proposals p ON p.id=e.proposal_id
          JOIN affected_evidence evidence ON evidence.id=p.primary_evidence_id
          JOIN care_item_basis b ON b.space_id=e.space_id AND b.item_id=e.result_item_id
            AND b.source_evidence_id=evidence.id
          WHERE e.space_id=:space AND e.status='APPLIED'
        ),
        affected_items AS (
          SELECT item_id FROM care_item_basis b WHERE b.space_id=:space
            AND (b.direct_document_id IN (SELECT id FROM affected_documents)
                 OR b.source_evidence_id IN (SELECT id FROM affected_evidence))
          UNION
          SELECT id FROM care_item WHERE space_id=:space
            AND current_document_id IN (SELECT id FROM affected_documents)
          UNION
          SELECT item_id FROM care_item_history WHERE space_id=:space
            AND document_id IN (SELECT id FROM affected_documents)
          UNION
          SELECT item_id FROM affected_decisions
        ),
        item_history AS (
          SELECT h.item_id,h.version,h.operation,h.document_id,
                 lag(h.document_id) OVER (PARTITION BY h.item_id ORDER BY h.version) AS previous_document
          FROM care_item_history h JOIN affected_items i ON i.item_id=h.item_id
          WHERE h.space_id=:space
        ),
        basis AS (
          SELECT b.id,b.item_id,b.direct_document_id,b.source_evidence_id,
            CASE WHEN b.source_evidence_id IS NOT NULL THEN
              e.quote IS NOT NULL AND r.lifecycle='ACTIVE' AND r.version=e.record_version
              AND NOT EXISTS (
                SELECT 1 FROM ai_proposal p WHERE p.space_id=b.space_id
                  AND p.primary_evidence_id=e.id AND care_document_available(p.output_document_id) IS NOT TRUE)
              AND NOT EXISTS (SELECT 1 FROM reached n WHERE n.kind='EVIDENCE' AND n.id=e.id)
            ELSE care_document_available(b.direct_document_id)
              AND NOT EXISTS (
                SELECT 1 FROM reached n WHERE n.kind='DOCUMENT' AND n.id=b.direct_document_id)
            END IS TRUE AS independent,
            CASE WHEN b.source_evidence_id IS NOT NULL THEN
              b.source_evidence_id IN (SELECT id FROM affected_evidence)
            ELSE b.direct_document_id IN (SELECT id FROM affected_documents)
            END AS affected
          FROM care_item_basis b JOIN affected_items i ON i.item_id=b.item_id
          LEFT JOIN evidence e ON e.space_id=b.space_id AND e.id=b.source_evidence_id
          LEFT JOIN source_record r ON r.space_id=e.space_id AND r.id=e.record_id
          WHERE b.space_id=:space
        ),
        item_impact AS (
          SELECT i.id,i.version,i.current_document_id,i.visibility,i.status,
            COALESCE(flags.independent,false) AS independent,
            i.visibility='VISIBLE' AND (i.needs_reconfirmation
              OR i.current_document_id IN (SELECT id FROM affected_documents)
              OR (COALESCE(flags.affected,false) AND NOT COALESCE(flags.independent,false))) AS reconfirm
          FROM care_item i JOIN affected_items affected ON affected.item_id=i.id
          LEFT JOIN (
            SELECT item_id,bool_or(independent) AS independent,bool_or(affected) AS affected
            FROM basis GROUP BY item_id
          ) flags ON flags.item_id=i.id
          WHERE i.space_id=:space
        ),
        snapshot(kind,id,version,independent,state) AS (
          SELECT 'ITEM',id,version,independent,jsonb_build_array(current_document_id,visibility,status)::text
          FROM item_impact
          UNION ALL
          SELECT 'HISTORY',h.item_id,h.version,false,
            jsonb_build_array(h.document_id,h.previous_document,h.operation)::text
          FROM item_history h WHERE h.document_id IN (SELECT id FROM affected_documents)
            OR h.previous_document IN (SELECT id FROM affected_documents)
            OR EXISTS (SELECT 1 FROM affected_decisions d WHERE d.item_id=h.item_id AND d.item_version=h.version)
          UNION ALL
          SELECT 'EVIDENCE',id,record_version,false,record_id::text FROM affected_evidence
          UNION ALL
          SELECT 'PROPOSAL',id,0,false,jsonb_build_array(resolution,invalidated_at IS NOT NULL)::text
          FROM affected_proposals
          UNION ALL
          SELECT 'BASIS',id,0,independent,jsonb_build_array(item_id,direct_document_id,source_evidence_id)::text
          FROM basis WHERE independent OR affected
          UNION ALL
          SELECT 'RUN',a.id,a.record_version,false,a.status
          FROM reached node JOIN analysis_run a ON a.id=node.id AND a.space_id=:space
          WHERE node.kind='RUN'
          UNION ALL
          SELECT 'DOCUMENT',id,0,false,'' FROM affected_documents
          UNION ALL
          SELECT 'REQUEST',e.id,0,false,jsonb_build_array(e.status,e.request_document_id,e.result_document_id)::text
          FROM change_request_entry e WHERE e.space_id=:space
            AND (e.request_document_id IN (SELECT id FROM affected_documents)
                 OR e.proposal_id IN (SELECT id FROM affected_proposals))
          UNION ALL
          SELECT DISTINCT 'EDGE',edge.from_id,0,false,
            jsonb_build_array(edge.from_kind,edge.to_kind,edge.to_id)::text
          FROM edges edge JOIN reached node ON node.kind=edge.from_kind AND node.id=edge.from_id
        )
        SELECT s.*, COALESCE(i.reconfirm,false) AS reconfirm
        FROM snapshot s LEFT JOIN item_impact i ON s.kind='ITEM' AND s.id=i.id
        ORDER BY s.kind,s.id,s.version,s.state
        """,
        Map.of("space", space, "record", record),
        (rs, row) ->
            new ImpactRow(
                Kind.valueOf(rs.getString("kind")),
                rs.getObject("id", UUID.class),
                rs.getLong("version"),
                rs.getBoolean("independent"),
                rs.getBoolean("reconfirm"),
                rs.getString("state")));
  }

  public record Access(boolean participating, boolean canDelete, Long version) {}

  public enum Kind {
    ITEM,
    HISTORY,
    EVIDENCE,
    PROPOSAL,
    BASIS,
    RUN,
    DOCUMENT,
    REQUEST,
    EDGE
  }

  public record ImpactRow(
      Kind kind, UUID id, long version, boolean independent, boolean reconfirm, String state) {}
}
