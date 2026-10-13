package com.hanmaum.backend.analysis.service;

import com.hanmaum.backend.ai.client.AiAnalysisResultValidator;
import com.hanmaum.backend.ai.dto.AiAnalysisRequest;
import com.hanmaum.backend.ai.dto.AiAnalysisResponse;
import com.hanmaum.backend.analysis.code.AnalysisFailureCode;
import com.hanmaum.backend.analysis.entity.AnalysisRun;
import com.hanmaum.backend.analysis.entity.AnalysisStatus;
import com.hanmaum.backend.analysis.repository.AnalysisRepository;
import com.hanmaum.backend.proposal.repository.AnalysisProposalRepository;
import com.hanmaum.backend.record.repository.SourceRecordRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisExecutionService {
  private final AnalysisRepository runs;
  private final SourceRecordRepository records;
  private final AnalysisProposalRepository proposals;
  private final AnalysisCandidateService candidates;
  private final AiAnalysisResultValidator results;

  public AnalysisExecutionService(
      AnalysisRepository runs,
      SourceRecordRepository records,
      AnalysisProposalRepository proposals,
      AnalysisCandidateService candidates,
      AiAnalysisResultValidator results) {
    this.runs = runs;
    this.records = records;
    this.proposals = proposals;
    this.candidates = candidates;
    this.results = results;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Optional<Work> claim(UUID space, UUID id, Duration lease) {
    if (lease == null || lease.isNegative() || lease.isZero())
      throw new IllegalArgumentException("Explicit positive lease required");
    boolean active = runs.lockSpace(space);
    var maybe = runs.lockRun(id);
    if (maybe.isEmpty()) return Optional.empty();
    var run = maybe.get();
    if (!run.spaceId().equals(space) || run.status() != AnalysisStatus.QUEUED)
      return Optional.empty();
    if (!validate(run, active)) return Optional.empty();
    var source = records.findActive(space, run.recordId()).orElseThrow();
    try {
      var inputs = candidates.candidates(space);
      var claimed = runs.claim(id, UUID.randomUUID(), lease);
      return Optional.of(
          new Work(
              claimed,
              new AiAnalysisRequest(
                  run.requestId(),
                  space,
                  run.recordId(),
                  source.body(),
                  run.inputRecordedAt(),
                  "Asia/Seoul",
                  inputs)));
    } catch (AnalysisCandidateService.CandidateIntegrationRequiredException exception) {
      runs.finish(run, AnalysisStatus.FAILED, AnalysisFailureCode.ANALYSIS_DEPENDENCY_UNAVAILABLE);
      return Optional.empty();
    }
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean complete(Work work, AiAnalysisResponse response) {
    boolean active = runs.lockSpace(work.run().spaceId());
    var run = runs.lockRun(work.run().id()).orElseThrow();
    if (!owns(run, work.run().workerToken())) return false;
    if (!validate(run, active)) return false;
    results.validate(work.request(), response);
    if (response.suggestions().stream()
        .anyMatch(s -> s.operation() != AiAnalysisResponse.Operation.CREATE)) {
      runs.finish(run, AnalysisStatus.FAILED, AnalysisFailureCode.ANALYSIS_DEPENDENCY_UNAVAILABLE);
      return false;
    }
    for (int i = 0; i < response.suggestions().size(); i++)
      proposals.insert(run, i, response.suggestions().get(i));
    runs.finish(run, AnalysisStatus.COMPLETED, null);
    return true;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void fail(Work work, AnalysisFailureCode failure) {
    boolean active = runs.lockSpace(work.run().spaceId());
    var maybe = runs.lockRun(work.run().id());
    if (maybe.isEmpty()) return;
    var run = maybe.get();
    if (owns(run, work.run().workerToken()) && validate(run, active))
      runs.finish(run, AnalysisStatus.FAILED, failure);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void recover(UUID space, UUID id) {
    boolean active = runs.lockSpace(space);
    var maybe = runs.lockRun(id);
    if (maybe.isEmpty()) return;
    var run = maybe.get();
    if (!run.spaceId().equals(space)
        || run.status() != AnalysisStatus.RUNNING
        || runs.liveLease(id, run.workerToken())) return;
    if (validate(run, active))
      runs.finish(run, AnalysisStatus.FAILED, AnalysisFailureCode.ANALYSIS_INTERRUPTED);
  }

  /** Future source deletion must invoke this in the SAME transaction as original erasure. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void invalidateSource(UUID space, UUID record) {
    runs.lockSpace(space);
    for (var run : runs.dependingOn(space, record)) {
      runs.lockRun(run.id());
      discard(run);
    }
  }

  private boolean owns(AnalysisRun run, UUID token) {
    return run.status() == AnalysisStatus.RUNNING
        && token.equals(run.workerToken())
        && runs.liveLease(run.id(), token);
  }

  private boolean validate(AnalysisRun run, boolean active) {
    if (!active || !runs.validInputs(run.id())) {
      discard(run);
      return false;
    }
    if (!runs.activeRequester(run)) {
      runs.finish(run, AnalysisStatus.FAILED, AnalysisFailureCode.ANALYSIS_REQUESTER_INACTIVE);
      return false;
    }
    return true;
  }

  private void discard(AnalysisRun run) {
    proposals.redact(run.id());
    runs.finish(run, AnalysisStatus.DISCARDED, null);
  }

  public record Work(AnalysisRun run, AiAnalysisRequest request) {
    @Override
    public String toString() {
      return "AnalysisWork[id=" + run.id() + "]";
    }
  }
}
