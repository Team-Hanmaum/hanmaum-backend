package com.hanmaum.backend.proposal.service;

import com.hanmaum.backend.global.command.CommandDigest;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Stateless, state-bound verification. Does not replace write-time authorization or locking. */
@Component
public class ProposalReviewToken {
  private final JdbcTemplate jdbc;
  private final CommandDigest digests;

  public ProposalReviewToken(JdbcTemplate jdbc, CommandDigest digests) {
    this.jdbc = jdbc;
    this.digests = digests;
  }

  public String issue(UUID actor, UUID space, UUID proposal) {
    var state =
        jdbc.query(
            """
        SELECT m.id member,p.output_document_id,p.primary_evidence_id,d.body::text body,
          COALESCE((SELECT jsonb_agg(jsonb_build_array(i.item_id,i.item_version,i.document_id) ORDER BY i.item_id)::text
            FROM analysis_input_item i WHERE i.run_id=p.run_id),'[]') inputs,
          COALESCE((SELECT jsonb_agg(jsonb_build_array(s.source_record_id,s.source_version) ORDER BY s.source_record_id)::text
            FROM analysis_input_source s WHERE s.run_id=p.run_id),'[]') sources,
          COALESCE((SELECT jsonb_agg(jsonb_build_array(b.basis_id,b.direct_document_id,b.source_evidence_id) ORDER BY b.basis_id)::text
            FROM analysis_input_basis b WHERE b.run_id=p.run_id),'[]') bases
        FROM ai_proposal p JOIN analysis_run a ON a.id=p.run_id
        JOIN content_document d ON d.id=p.output_document_id
        JOIN care_space space ON space.id=p.space_id AND space.lifecycle='ACTIVE'
        JOIN space_membership m ON m.space_id=p.space_id AND m.user_id=? AND m.is_active
        WHERE p.space_id=? AND p.id=? AND p.resolution='OPEN' AND p.invalidated_at IS NULL
          AND a.status='COMPLETED' AND a.invalidated_at IS NULL AND analysis_inputs_valid(a.id)
          AND care_document_available(d.id)
          AND EXISTS (SELECT 1 FROM evidence e WHERE e.id=p.primary_evidence_id AND e.quote IS NOT NULL AND e.redacted_at IS NULL)
        """,
            (rs, n) ->
                digests.digest(
                    "PROPOSAL_REVIEW",
                    actor.toString(),
                    space.toString(),
                    proposal.toString(),
                    rs.getString("member"),
                    rs.getString("output_document_id"),
                    rs.getString("primary_evidence_id"),
                    rs.getString("body"),
                    rs.getString("inputs"),
                    rs.getString("sources"),
                    rs.getString("bases")),
            actor,
            space,
            proposal);
    return state.isEmpty() ? null : state.getFirst();
  }
}
