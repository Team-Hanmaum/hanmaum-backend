package com.hanmaum.backend.record.service;

import com.hanmaum.backend.careitem.service.CareMutationInput;
import com.hanmaum.backend.carespace.service.CareSpaceAccessService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.CommandDigest;
import com.hanmaum.backend.global.command.CommandReceipt;
import com.hanmaum.backend.global.command.CommandReceiptRepository;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.record.dto.DeleteSourceRecordRequest;
import com.hanmaum.backend.record.dto.DeleteSourceRecordResponse;
import com.hanmaum.backend.record.dto.SourceRecordDeletionImpact.AffectedItem;
import com.hanmaum.backend.record.repository.SourceRecordDeletionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceRecordDeletionService {
  private static final String KIND = "DELETE_SOURCE_RECORD";
  private final SourceRecordDeletionRepository records;
  private final SourceRecordDeletionImpactService impacts;
  private final CareSpaceAccessService access;
  private final CommandReceiptRepository receipts;
  private final CommandDigest digest;
  private final Clock clock;

  public SourceRecordDeletionService(
      SourceRecordDeletionRepository records,
      SourceRecordDeletionImpactService impacts,
      CareSpaceAccessService access,
      CommandReceiptRepository receipts,
      CommandDigest digest,
      Clock clock) {
    this.records = records;
    this.impacts = impacts;
    this.access = access;
    this.receipts = receipts;
    this.digest = digest;
    this.clock = clock;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public DeleteSourceRecordResponse delete(
      UUID actor, UUID space, UUID record, DeleteSourceRecordRequest request) {
    long expected =
        CareMutationInput.version(request.expectedRecordVersion(), "expectedRecordVersion", true);
    String impactToken = CareMutationInput.text(request.impactToken(), "impactToken", true);
    UUID requestId = request.clientRequestId();
    String scope = "SPACE:" + space + ":SOURCE_RECORD:" + record;
    String hash =
        digest.digest(
            actor.toString(),
            requestId.toString(),
            KIND,
            scope,
            Long.toString(expected),
            impactToken);
    if (!receipts.tryLock(actor, requestId))
      throw new ApiException(CommonErrorCode.REQUEST_IN_PROGRESS);

    var previous = receipts.find(actor, requestId);
    // Proposal decisions, item mutations and AI result storage use this same space lock.
    UUID membership = access.requireActiveMember(actor, space).membershipId();
    if (previous.isPresent()) {
      var receipt = previous.get();
      if (!KIND.equals(receipt.commandKind())
          || !scope.equals(receipt.scopeKey())
          || !hash.equals(receipt.requestDigest()))
        throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
      impacts.requireVersion(actor, space, record, true);
      var refs = receipt.resultRefs();
      if (!membership.toString().equals(refs.get("membershipId")))
        throw new ApiException(CommonErrorCode.FORBIDDEN);
      return new DeleteSourceRecordResponse(
          record,
          Instant.parse(refs.get("deletedAt")),
          readIds(refs.get("affectedItemIds")),
          readIds(refs.get("reconfirmationItemIds")));
    }

    long version = impacts.requireVersion(actor, space, record, false);
    if (version != expected) throw new ApiException(CommonErrorCode.VERSION_CONFLICT);
    var snapshot = impacts.snapshot(actor, space, record, version);
    if (!MessageDigest.isEqual(
        snapshot.impact().impactToken().getBytes(StandardCharsets.UTF_8),
        impactToken.getBytes(StandardCharsets.UTF_8)))
      throw new ApiException(CommonErrorCode.DELETION_IMPACT_CHANGED);

    var affected = snapshot.impact().affectedItems().stream().map(AffectedItem::itemId).toList();
    var reconfirmation =
        snapshot.impact().affectedItems().stream()
            .filter(AffectedItem::requiresReconfirmation)
            .map(AffectedItem::itemId)
            .toList();
    var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    records.erase(space, record, version, now, snapshot.rows(), affected, reconfirmation);
    receipts.saveSuccess(
        actor,
        requestId,
        new CommandReceipt(
            KIND,
            scope,
            hash,
            Map.of(
                "membershipId", membership.toString(),
                "deletedAt", now.toString(),
                "affectedItemIds", writeIds(affected),
                "reconfirmationItemIds", writeIds(reconfirmation))),
        now);
    return new DeleteSourceRecordResponse(record, now, affected, reconfirmation);
  }

  private static String writeIds(List<UUID> ids) {
    return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
  }

  private static List<UUID> readIds(String ids) {
    return ids.isEmpty() ? List.of() : Arrays.stream(ids.split(",")).map(UUID::fromString).toList();
  }
}
