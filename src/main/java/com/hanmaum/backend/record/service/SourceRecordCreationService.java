package com.hanmaum.backend.record.service;

import com.hanmaum.backend.analysis.dto.AnalysisSummary;
import com.hanmaum.backend.analysis.service.AnalysisSubmissionService;
import com.hanmaum.backend.carespace.service.CareSpaceAccessService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.CommandDigest;
import com.hanmaum.backend.global.command.CommandReceipt;
import com.hanmaum.backend.global.command.CommandReceiptRepository;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiFieldError;
import com.hanmaum.backend.record.dto.CreateSourceRecordRequest;
import com.hanmaum.backend.record.dto.CreateSourceRecordResponse;
import com.hanmaum.backend.record.entity.SourceRecord;
import com.hanmaum.backend.record.repository.SourceRecordRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceRecordCreationService {
  private static final String KIND = "CREATE_SOURCE_RECORD";
  private final SourceRecordRepository records;
  private final CommandReceiptRepository receipts;
  private final CommandDigest digests;
  private final CareSpaceAccessService access;
  private final Clock clock;
  private final AnalysisSubmissionService analyses;

  public SourceRecordCreationService(
      SourceRecordRepository records,
      CommandReceiptRepository receipts,
      CommandDigest digests,
      CareSpaceAccessService access,
      Clock clock,
      AnalysisSubmissionService analyses) {
    this.records = records;
    this.receipts = receipts;
    this.digests = digests;
    this.access = access;
    this.clock = clock;
    this.analyses = analyses;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public CreateSourceRecordResponse create(
      UUID userId, UUID spaceId, CreateSourceRecordRequest request) {
    String content = SourceContent.validate(request.content());
    UUID requestId = request.clientRequestId();
    if (requestId == null)
      throw new ApiException(
          CommonErrorCode.INVALID_REQUEST,
          List.of(new ApiFieldError("clientRequestId", "요청 ID를 입력해주세요.")));
    String scope = "SPACE:" + spaceId;
    String digest = digests.digest(userId.toString(), requestId.toString(), KIND, scope, content);
    if (!receipts.tryLock(userId, requestId))
      throw new ApiException(CommonErrorCode.REQUEST_IN_PROGRESS);
    var previous = receipts.find(userId, requestId);
    if (previous.isPresent()) {
      var receipt = previous.get();
      if (!KIND.equals(receipt.commandKind())
          || !scope.equals(receipt.scopeKey())
          || !digest.equals(receipt.requestDigest()))
        throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
      var current = access.requireActiveMember(userId, spaceId);
      if (!current.membershipId().toString().equals(receipt.resultRefs().get("membershipId"))) {
        throw new ApiException(CommonErrorCode.FORBIDDEN);
      }
      // Never resurrect erased records or serve a cached original from a receipt.
      var record =
          records
              .findActive(spaceId, UUID.fromString(receipt.resultRefs().get("recordId")))
              .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
      return response(record, analyses.initialAcceptance(spaceId, record.id()));
    }
    var current = access.requireActiveMember(userId, spaceId);
    var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    var record =
        new SourceRecord(UUID.randomUUID(), spaceId, current.membershipId(), content, now, 1);
    receipts.saveSuccess(
        userId,
        requestId,
        new CommandReceipt(
            KIND,
            scope,
            digest,
            Map.of(
                "recordId",
                record.id().toString(),
                "membershipId",
                current.membershipId().toString())),
        now);
    records.insert(record, userId, requestId);
    var analysis = analyses.enqueueInitial(record, userId, requestId);
    return response(record, analysis);
  }

  private CreateSourceRecordResponse response(SourceRecord record, AnalysisSummary analysis) {
    return new CreateSourceRecordResponse(
        record.id(), Long.toString(record.version()), record.recordedAt(), analysis);
  }
}
