package com.hanmaum.backend.analysis.service;

import com.hanmaum.backend.ai.dto.AiAnalysisRequest.CandidateItem;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnalysisCandidateService {
  private final JdbcTemplate jdbc;

  public AnalysisCandidateService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<CandidateItem> candidates(UUID spaceId) {
    // There is currently no CareItem storage. Fail closed when that domain is introduced;
    // its service must supply same-space candidates AND register their source provenance.
    if (Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT to_regclass('care_item') IS NOT NULL", Boolean.class)))
      throw new CandidateIntegrationRequiredException();
    return List.of();
  }

  public static class CandidateIntegrationRequiredException extends RuntimeException {}
}
