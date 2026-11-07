package com.hanmaum.backend.carespace.service;

import com.hanmaum.backend.careitem.service.CareItemInput;
import com.hanmaum.backend.careitem.service.CareMutationInput;
import com.hanmaum.backend.carespace.dto.DeleteCareSpaceResponse;
import com.hanmaum.backend.carespace.repository.CareSpaceDeletionRepository;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.*;
import com.hanmaum.backend.global.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class CareSpaceDeletionService {
  private final CareSpaceAccessService access;
  private final CareSpaceDeletionImpactService impacts;
  private final CareSpaceDeletionRepository spaces;
  private final CommandReceiptRepository receipts;
  private final CommandDigest digests;
  private final Clock clock;

  public CareSpaceDeletionService(
      CareSpaceAccessService access,
      CareSpaceDeletionImpactService impacts,
      CareSpaceDeletionRepository spaces,
      CommandReceiptRepository receipts,
      CommandDigest digests,
      Clock clock) {
    this.access = access;
    this.impacts = impacts;
    this.spaces = spaces;
    this.receipts = receipts;
    this.digests = digests;
    this.clock = clock;
  }

  /**
   * Completes all service-data deletion before responding; retries never recreate a deleted space.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public DeleteCareSpaceResponse delete(UUID actor, UUID space, JsonNode body) {
    CareMutationInput.object(
        body, "body", Set.of("expectedSpaceVersion", "impactToken", "clientRequestId"));
    long expected =
        CareMutationInput.version(body.path("expectedSpaceVersion"), "expectedSpaceVersion", true);
    UUID key = CareMutationInput.uuid(body.path("clientRequestId"), "clientRequestId", true);
    String token = CareMutationInput.text(body.path("impactToken"), "impactToken", true);
    if (!token.matches("[0-9a-f]{64}"))
      throw CareItemInput.invalid("impactToken", "영향 확인값을 확인해주세요.");
    String kind = "DELETE_CARE_SPACE", scope = "SPACE:" + space;
    String digest =
        digests.digest(
            actor.toString(), key.toString(), kind, scope, Long.toString(expected), token);
    if (!receipts.tryLock(actor, key)) throw new ApiException(CommonErrorCode.REQUEST_IN_PROGRESS);
    var previous = receipts.find(actor, key);
    if (previous.isPresent()) {
      var saved = previous.get();
      if (!kind.equals(saved.commandKind())
          || !scope.equals(saved.scopeKey())
          || !digest.equals(saved.requestDigest()))
        throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
      // Agreed deletion contract: no post-deletion success/authority exception. Retain the global
      // digest tombstone so a lost response cannot make any old command execute a second time.
      throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
    }
    var current = access.requireOwner(actor, space);
    if (current.version() != expected) throw new ApiException(CommonErrorCode.VERSION_CONFLICT);
    var impact = impacts.snapshot(actor, space, expected);
    if (!MessageDigest.isEqual(
        impact.impactToken().getBytes(StandardCharsets.US_ASCII),
        token.getBytes(StandardCharsets.US_ASCII)))
      throw new ApiException(CommonErrorCode.DELETION_IMPACT_CHANGED);
    var started = clock.instant().truncatedTo(ChronoUnit.MICROS);
    spaces.purge(space);
    var deletedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    receipts.saveSuccess(
        actor,
        key,
        new CommandReceipt(kind, scope, digest, Map.of("deletedAt", deletedAt.toString())),
        started);
    return new DeleteCareSpaceResponse(space, deletedAt);
  }
}
