package com.hanmaum.backend.proposal.repository;

import com.hanmaum.backend.proposal.dto.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class ProposalDecisionRepository {
  private final JdbcTemplate jdbc;

  public ProposalDecisionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Proposal> lock(UUID space, UUID id) {
    return jdbc
        .query(
            """
        SELECT p.*,d.body::text body,analysis_inputs_valid(p.run_id)
          AND a.status='COMPLETED' AND a.invalidated_at IS NULL
          AND p.invalidated_at IS NULL AND care_document_available(d.id)
          AND e.quote IS NOT NULL AND e.redacted_at IS NULL AS available
        FROM ai_proposal p JOIN analysis_run a ON a.id=p.run_id
        JOIN content_document d ON d.id=p.output_document_id
        JOIN evidence e ON e.id=p.primary_evidence_id
        WHERE p.space_id=? AND p.id=? FOR UPDATE OF p
        """,
            (rs, n) ->
                new Proposal(
                    id,
                    rs.getObject("run_id", UUID.class),
                    rs.getString("item_type"),
                    rs.getString("ai_operation"),
                    rs.getString("resolution"),
                    rs.getBoolean("available"),
                    rs.getString("body"),
                    rs.getObject("output_document_id", UUID.class),
                    rs.getObject("primary_evidence_id", UUID.class)),
            space,
            id)
        .stream()
        .findFirst();
  }

  public void batch(UUID batch, UUID space, UUID member, boolean applied, Instant now) {
    jdbc.update(
        "INSERT INTO proposal_decision_batch(id,space_id,decided_by_membership_id,decision,decided_at) VALUES (?,?,?,?,?)",
        batch,
        space,
        member,
        applied ? "APPLIED" : "DECLINED",
        Timestamp.from(now));
  }

  public void decision(
      UUID space, UUID batch, int index, UUID proposal, UUID item, Long version, UUID document) {
    jdbc.update(
        """
        INSERT INTO proposal_decision(space_id,batch_id,ordinal_no,proposal_id,item_id,item_version,document_id)
        VALUES (?,?,?,?,?,?,?)
        """,
        space,
        batch,
        index,
        proposal,
        item,
        version,
        document);
    if (jdbc.update(
            "UPDATE ai_proposal SET resolution=? WHERE space_id=? AND id=? AND resolution='OPEN'",
            item == null ? "DECLINED" : "APPLIED",
            space,
            proposal)
        != 1) throw new IllegalStateException("Decision lost its locked proposal");
  }

  public ProposalApplicationResponse application(UUID space, UUID batch) {
    Instant at =
        jdbc.queryForObject(
            "SELECT decided_at FROM proposal_decision_batch WHERE space_id=? AND id=?",
            (rs, n) -> rs.getTimestamp(1).toInstant(),
            space,
            batch);
    var results =
        jdbc.query(
            "SELECT proposal_id,item_id,item_version FROM proposal_decision WHERE space_id=? AND batch_id=? ORDER BY ordinal_no",
            (rs, n) ->
                new ProposalApplicationResponse.Result(
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)),
            space,
            batch);
    return new ProposalApplicationResponse(batch, at, results);
  }

  public ProposalDeclineResponse decline(UUID space, UUID batch) {
    Instant at =
        jdbc.queryForObject(
            "SELECT decided_at FROM proposal_decision_batch WHERE space_id=? AND id=?",
            (rs, n) -> rs.getTimestamp(1).toInstant(),
            space,
            batch);
    return new ProposalDeclineResponse(
        batch,
        jdbc.query(
            "SELECT proposal_id FROM proposal_decision WHERE space_id=? AND batch_id=? ORDER BY ordinal_no",
            (rs, n) -> rs.getObject(1, UUID.class),
            space,
            batch),
        at);
  }

  public record Proposal(
      UUID id,
      UUID run,
      String type,
      String operation,
      String resolution,
      boolean available,
      String body,
      UUID document,
      UUID evidence) {
    @Override
    public String toString() {
      return "Proposal[id=" + id + "]";
    }
  }
}
