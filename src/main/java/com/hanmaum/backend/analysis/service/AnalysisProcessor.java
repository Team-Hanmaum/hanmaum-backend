package com.hanmaum.backend.analysis.service;

import com.hanmaum.backend.ai.client.AiServiceException;
import com.hanmaum.backend.ai.client.HanmaumAiClient;
import com.hanmaum.backend.ai.client.v2.AiV2Exception;
import com.hanmaum.backend.ai.client.v2.HanmaumAiV2Client;
import com.hanmaum.backend.analysis.code.AnalysisFailureCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisProcessor {
  private final HanmaumAiClient client;
  private final AnalysisExecutionService execution;
  private final HanmaumAiV2Client v2;

  public AnalysisProcessor(
      HanmaumAiClient client, AnalysisExecutionService execution, HanmaumAiV2Client v2) {
    this.client = client;
    this.execution = execution;
    this.v2 = v2;
  }

  @Transactional(propagation = Propagation.NEVER)
  public void process(AnalysisExecutionService.Work work) {
    try {
      if (work.run().contractVersion().equals("v2")) {
        var result = v2.analyze(work.v2Request());
        if (Thread.currentThread().isInterrupted())
          execution.fail(work, AnalysisFailureCode.ANALYSIS_INTERRUPTED);
        else execution.completeV2(work, result);
        return;
      }
      var result = client.analyze(work.request());
      if (Thread.currentThread().isInterrupted())
        execution.fail(work, AnalysisFailureCode.ANALYSIS_INTERRUPTED);
      else execution.complete(work, result);
    } catch (AiV2Exception exception) {
      execution.fail(work, AnalysisFailureCode.valueOf(exception.failureCode().name()));
    } catch (AiServiceException exception) {
      execution.fail(work, AnalysisFailureCode.AI_SERVICE_UNAVAILABLE);
    } catch (RuntimeException exception) {
      // A failed storage transaction rolls back all proposals; only safe metadata is persisted.
      execution.fail(work, AnalysisFailureCode.ANALYSIS_STORAGE_FAILED);
    }
  }
}
