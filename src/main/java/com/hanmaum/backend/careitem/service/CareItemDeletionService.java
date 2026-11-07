package com.hanmaum.backend.careitem.service;

import com.hanmaum.backend.careitem.dto.DeleteCareItemResponse;
import com.hanmaum.backend.carespace.service.CareSpaceAccessService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.*;
import com.hanmaum.backend.global.exception.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Soft deletion retains provenance; source erasure remains a separate content-removal operation.
 */
@Service
@Transactional
public class CareItemDeletionService {
  private final CareSpaceAccessService access;
  private final CareItemApplicationService applications;
  private final EditLockService locks;
  private final CommandReceiptRepository receipts;
  private final CommandDigest digests;
  private final ObjectMapper mapper;
  private final Clock clock;

  public CareItemDeletionService(
      CareSpaceAccessService access,
      CareItemApplicationService applications,
      EditLockService locks,
      CommandReceiptRepository receipts,
      CommandDigest digests,
      ObjectMapper mapper,
      Clock clock) {
    this.access = access;
    this.applications = applications;
    this.locks = locks;
    this.receipts = receipts;
    this.digests = digests;
    this.mapper = mapper;
    this.clock = clock;
  }

  public DeleteCareItemResponse delete(UUID actor, UUID space, UUID item, JsonNode input) {
    CareMutationInput.object(
        input, "body", Set.of("clientRequestId", "expectedItemVersion", "leaseToken"));
    CareMutationSizeLimits.strings(input, "body");
    CareMutationSizeLimits.bytes(mapper, input, CareMutationSizeLimits.REQUEST_BYTES, "body");
    UUID key = CareMutationInput.uuid(input.path("clientRequestId"), "clientRequestId", true);
    long expected =
        CareMutationInput.version(input.path("expectedItemVersion"), "expectedItemVersion", true);
    String lease = CareMutationInput.text(input.path("leaseToken"), "leaseToken", true);
    String kind = "DELETE_CARE_ITEM", scope = "SPACE:" + space + ":ITEM:" + item;
    String digest =
        digests.digest(actor.toString(), key.toString(), kind, scope, Long.toString(expected));
    if (!receipts.tryLock(actor, key)) throw new ApiException(CommonErrorCode.REQUEST_IN_PROGRESS);
    var member = access.requireOwner(actor, space);
    var target = applications.lock(space, item);
    var prior = receipts.find(actor, key);
    if (prior.isPresent()) {
      var saved = prior.get();
      if (!kind.equals(saved.commandKind())
          || !scope.equals(saved.scopeKey())
          || !digest.equals(saved.requestDigest()))
        throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
      var refs = saved.resultRefs();
      if (!member.membershipId().toString().equals(refs.get("membershipId")))
        throw new ApiException(CommonErrorCode.FORBIDDEN);
      return new DeleteCareItemResponse(
          item, refs.get("version"), "DELETED", Instant.parse(refs.get("deletedAt")));
    }
    var prepared = applications.prepareDeletion(target, expected);
    locks.requireForDeletion(space, item, member.membershipId(), lease);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    var result = applications.delete(space, member.membershipId(), prepared, now);
    locks.releaseAfterSave(space, item);
    String version = Long.toString(result.version());
    receipts.saveSuccess(
        actor,
        key,
        new CommandReceipt(
            kind,
            scope,
            digest,
            Map.of(
                "membershipId",
                member.membershipId().toString(),
                "itemId",
                item.toString(),
                "version",
                version,
                "deletedAt",
                now.toString())),
        now);
    return new DeleteCareItemResponse(item, version, "DELETED", now);
  }
}
