package com.hanmaum.backend.analysis.service;

import com.hanmaum.backend.ai.client.AiServiceException;
import com.hanmaum.backend.ai.client.HanmaumAiClient;
import com.hanmaum.backend.analysis.code.AnalysisFailureCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisProcessor {
  private final HanmaumAiClient client;
  private final AnalysisExecutionService execution;

  public AnalysisProcessor(HanmaumAiClient client, AnalysisExecutionService execution) {
    this.client = client;
    this.execution = execution;
  }

  @Transactional(propagation = Propagation.NEVER)
  public void process(AnalysisExecutionService.Work work) {
    try {
      var result = client.analyze(work.request());
      if (Thread.currentThread().isInterrupted())
        execution.fail(work, AnalysisFailureCode.ANALYSIS_INTERRUPTED);
      else execution.complete(work, result);
    } catch (AiServiceException exception) {
      execution.fail(work, AnalysisFailureCode.AI_SERVICE_UNAVAILABLE);
    } catch (RuntimeException exception) {
      // A failed storage transaction rolls back all proposals; only safe metadata is persisted.
      execution.fail(work, AnalysisFailureCode.ANALYSIS_STORAGE_FAILED);
    }
  }
}
