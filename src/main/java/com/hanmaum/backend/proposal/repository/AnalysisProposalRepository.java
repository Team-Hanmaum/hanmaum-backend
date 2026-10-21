package com.hanmaum.backend.proposal.repository;

import com.hanmaum.backend.ai.dto.AiAnalysisResponse;
import com.hanmaum.backend.analysis.entity.AnalysisRun;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Unconfirmed v1/v2 proposal storage; no approval or CareItem mutation. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class AnalysisProposalRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public AnalysisProposalRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public void insert(AnalysisRun run, int ordinal, AiAnalysisResponse.Suggestion suggestion) {
    UUID document = UUID.randomUUID();
    UUID evidence = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO content_document (id, space_id, kind, body, created_at)
        VALUES (?, ?, 'AI_OUTPUT', CAST(? AS jsonb), clock_timestamp())
        """,
        document,
        run.spaceId(),
        mapper.writeValueAsString(
            Map.of("title", suggestion.title(), "changes", suggestion.changes())));
    jdbc.update(
        """
        INSERT INTO evidence (id, space_id, record_id, record_version, quote, created_at)
        VALUES (?, ?, ?, ?, ?, clock_timestamp())
        """,
        evidence,
        run.spaceId(),
        run.recordId(),
        run.recordVersion(),
        suggestion.evidence());
    jdbc.update(
        """
        INSERT INTO ai_proposal (id, space_id, run_id, ordinal_no, ai_operation, item_type,
          output_document_id, primary_evidence_id, resolution, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', clock_timestamp())
        """,
        UUID.randomUUID(),
        run.spaceId(),
        run.id(),
        ordinal,
        suggestion.operation().name(),
        suggestion.type().name(),
        document,
        evidence);
  }

  public void redact(UUID runId) {
    jdbc.update(
        """
        UPDATE content_document d SET body = NULL, redacted_at = COALESCE(d.redacted_at, clock_timestamp())
        FROM ai_proposal p WHERE p.run_id = ? AND d.id = p.output_document_id
        """,
        runId);
    jdbc.update(
        """
        UPDATE evidence e SET quote = NULL, redacted_at = COALESCE(e.redacted_at, clock_timestamp())
        FROM ai_proposal p WHERE p.run_id = ? AND e.id = p.primary_evidence_id
        """,
        runId);
    jdbc.update(
        "UPDATE ai_proposal SET invalidated_at = COALESCE(invalidated_at, clock_timestamp()) WHERE run_id = ?",
        runId);
  }

  public void insertV2(
      AnalysisRun run,
      int ordinal,
      com.hanmaum.backend.ai.dto.v2.AiV2AnalysisResponse.Suggestion suggestion) {
    UUID document = UUID.randomUUID(), evidence = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO content_document (id,space_id,kind,body,created_at) VALUES (?,?,'AI_OUTPUT',?::jsonb,clock_timestamp())",
        document,
        run.spaceId(),
        mapper.writeValueAsString(suggestion));
    jdbc.update(
        "INSERT INTO evidence (id,space_id,record_id,record_version,quote,created_at) VALUES (?,?,?,?,?,clock_timestamp())",
        evidence,
        run.spaceId(),
        run.recordId(),
        run.recordVersion(),
        suggestion.evidence());
    jdbc.update(
        """
        INSERT INTO ai_proposal (id,space_id,run_id,ordinal_no,ai_operation,item_type,output_document_id,primary_evidence_id,resolution,created_at)
        VALUES (?,?,?,?,?,?,?,?,'OPEN',clock_timestamp())
        """,
        UUID.randomUUID(),
        run.spaceId(),
        run.id(),
        ordinal,
        suggestion.operation().name(),
        suggestion.type().name(),
        document,
        evidence);
  }
}
