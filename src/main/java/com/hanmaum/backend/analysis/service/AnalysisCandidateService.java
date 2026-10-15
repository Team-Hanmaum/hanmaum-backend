package com.hanmaum.backend.analysis.service;

import com.hanmaum.backend.ai.dto.AiAnalysisRequest.CandidateItem;
import com.hanmaum.backend.careitem.service.CareItemCandidateService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AnalysisCandidateService {
  private final CareItemCandidateService items;

  public AnalysisCandidateService(CareItemCandidateService items) {
    this.items = items;
  }

  public List<CandidateItem> candidates(UUID spaceId, UUID runId) {
    return items.capture(spaceId, runId);
  }

  public static class CandidateIntegrationRequiredException extends RuntimeException {}
}
