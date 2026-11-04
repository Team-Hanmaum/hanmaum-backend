package com.hanmaum.backend.proposal.repository;

import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

/** Proposal-owned parts of provenance read models; no payload leaves this repository. */
@Repository
public class ProposalProvenanceRepository {
  private final NamedParameterJdbcTemplate jdbc;

  public ProposalProvenanceRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Set<UUID> unavailableEvidence(UUID space, Collection<UUID> evidenceIds) {
    if (evidenceIds.isEmpty()) return Set.of();
    return new HashSet<>(
        jdbc.query(
            """
        SELECT p.primary_evidence_id FROM ai_proposal p
        WHERE p.space_id=:space AND p.primary_evidence_id=ANY(:evidence)
          AND care_document_available(p.output_document_id) IS NOT TRUE
        """,
            new MapSqlParameterSource("space", space)
                .addValue("evidence", new SqlArrayValue("uuid", evidenceIds.toArray())),
            (rs, n) -> rs.getObject(1, UUID.class)));
  }

  public List<UUID> appliedEvidence(UUID space, UUID item, long version) {
    return jdbc.query(
        """
        SELECT p.primary_evidence_id FROM proposal_decision decision
        JOIN ai_proposal p ON p.space_id=decision.space_id AND p.id=decision.proposal_id
        WHERE decision.space_id=:space AND decision.item_id=:item AND decision.item_version=:version
        UNION
        SELECT p.primary_evidence_id FROM change_request_entry entry
        JOIN ai_proposal p ON p.space_id=entry.space_id AND p.id=entry.proposal_id
        WHERE entry.space_id=:space AND entry.result_item_id=:item
          AND entry.result_item_version=:version AND entry.status='APPLIED'
          AND entry.proposal_id IS NOT NULL
        """,
        new MapSqlParameterSource("space", space)
            .addValue("item", item)
            .addValue("version", version),
        (rs, n) -> rs.getObject(1, UUID.class));
  }
}
