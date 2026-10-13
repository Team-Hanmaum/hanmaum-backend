package com.hanmaum.backend.analysis.service;

import com.hanmaum.backend.analysis.code.AnalysisErrorCode;
import com.hanmaum.backend.analysis.dto.AnalysisAccepted;
import com.hanmaum.backend.analysis.dto.AnalysisSummary;
import com.hanmaum.backend.analysis.dto.RetryAnalysisRequest;
import com.hanmaum.backend.analysis.entity.AnalysisStatus;
import com.hanmaum.backend.analysis.repository.AnalysisRepository;
import com.hanmaum.backend.carespace.service.CareSpaceAccessService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.CommandDigest;
import com.hanmaum.backend.global.command.CommandReceipt;
import com.hanmaum.backend.global.command.CommandReceiptRepository;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiFieldError;
import com.hanmaum.backend.record.entity.SourceRecord;
import com.hanmaum.backend.record.repository.SourceRecordRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisSubmissionService {
  private static final String KIND = "RETRY_SOURCE_ANALYSIS";
  private final AnalysisRepository runs;
  private final SourceRecordRepository records;
  private final CareSpaceAccessService access;
  private final CommandReceiptRepository receipts;
  private final CommandDigest digests;
  private final Clock clock;

  public AnalysisSubmissionService(
      AnalysisRepository runs,
      SourceRecordRepository records,
      CareSpaceAccessService access,
      CommandReceiptRepository receipts,
      CommandDigest digests,
      Clock clock) {
    this.runs = runs;
    this.records = records;
    this.access = access;
    this.receipts = receipts;
    this.digests = digests;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public AnalysisSummary enqueueInitial(SourceRecord record, UUID actor, UUID command) {
    UUID id =
        runs.enqueue(record, record.authorMembershipId(), null, actor, command, clock.instant());
    return new AnalysisSummary(id, AnalysisStatus.QUEUED);
  }

  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public AnalysisSummary initialAcceptance(UUID space, UUID record) {
    // Historical V5 receipts have no analysis; replay must not silently start a new one.
    return runs.initial(space, record)
        .map(r -> new AnalysisSummary(r.id(), AnalysisStatus.QUEUED))
        .orElse(null);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public AnalysisAccepted retry(
      UUID user, UUID space, UUID recordId, RetryAnalysisRequest request) {
    if (request.failedAnalysisId() == null || request.clientRequestId() == null)
      throw new ApiException(CommonErrorCode.INVALID_REQUEST);
    long version;
    try {
      if (request.expectedRecordVersion() == null
          || !request.expectedRecordVersion().matches("[1-9][0-9]*"))
        throw new NumberFormatException();
      version = Long.parseLong(request.expectedRecordVersion());
    } catch (NumberFormatException exception) {
      throw new ApiException(
          CommonErrorCode.INVALID_REQUEST,
          List.of(new ApiFieldError("expectedRecordVersion", "유효한 버전 문자열을 입력해주세요.")));
    }
    UUID key = request.clientRequestId();
    String scope = "SOURCE_RECORD:" + space + ":" + recordId;
    String digest =
        digests.digest(
            user.toString(),
            key.toString(),
            KIND,
            scope,
            request.failedAnalysisId().toString(),
            request.expectedRecordVersion());
    if (!receipts.tryLock(user, key)) throw new ApiException(CommonErrorCode.REQUEST_IN_PROGRESS);
    var previous = receipts.find(user, key);
    if (previous.isPresent()) {
      var saved = previous.get();
      if (!KIND.equals(saved.commandKind())
          || !scope.equals(saved.scopeKey())
          || !digest.equals(saved.requestDigest()))
        throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
      var member = access.requireActiveMember(user, space);
      if (!member.membershipId().toString().equals(saved.resultRefs().get("membershipId")))
        throw new ApiException(CommonErrorCode.FORBIDDEN);
      records
          .findActive(space, recordId)
          .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
      UUID id = UUID.fromString(saved.resultRefs().get("analysisId"));
      var run =
          runs.find(space, recordId, id)
              .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
      if (run.status() == AnalysisStatus.DISCARDED)
        throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
      return new AnalysisAccepted(recordId, id, AnalysisStatus.QUEUED);
    }
    var member = access.requireActiveMember(user, space);
    var record =
        records
            .findActive(space, recordId)
            .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    var failed =
        runs.find(space, recordId, request.failedAnalysisId())
            .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    if (record.version() != version) throw new ApiException(CommonErrorCode.VERSION_CONFLICT);
    var latest = runs.latest(space, recordId).orElseThrow();
    if (!latest.id().equals(failed.id()) || failed.status() != AnalysisStatus.FAILED)
      throw new ApiException(AnalysisErrorCode.ANALYSIS_STATE_CONFLICT);
    UUID id = runs.enqueue(record, member.membershipId(), failed, null, null, clock.instant());
    receipts.saveSuccess(
        user,
        key,
        new CommandReceipt(
            KIND,
            scope,
            digest,
            Map.of(
                "recordId",
                recordId.toString(),
                "analysisId",
                id.toString(),
                "membershipId",
                member.membershipId().toString())),
        clock.instant());
    runs.attachCommand(id, user, key);
    return new AnalysisAccepted(recordId, id, AnalysisStatus.QUEUED);
  }
}
