package com.hanmaum.backend.user.service;

import com.hanmaum.backend.careitem.service.CareItemApplicationService;
import com.hanmaum.backend.careitem.service.CareMutationInput;
import com.hanmaum.backend.careitem.service.EditLockService;
import com.hanmaum.backend.carespace.repository.MembershipLifecycleRepository;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.CommandReceiptRepository;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.user.code.UserErrorCode;
import com.hanmaum.backend.user.repository.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class MemberWithdrawalService {
  private final MemberWithdrawalRepository members;
  private final SocialAccountIdentityLockRepository identities;
  private final MemberLifecycleAccess lifecycle;
  private final MembershipLifecycleRepository memberships;
  private final CareItemApplicationService items;
  private final EditLockService editLocks;
  private final CommandReceiptRepository receipts;
  private final Clock clock;

  public MemberWithdrawalService(
      MemberWithdrawalRepository members,
      SocialAccountIdentityLockRepository identities,
      MemberLifecycleAccess lifecycle,
      MembershipLifecycleRepository memberships,
      CareItemApplicationService items,
      EditLockService editLocks,
      CommandReceiptRepository receipts,
      Clock clock) {
    this.members = members;
    this.identities = identities;
    this.lifecycle = lifecycle;
    this.memberships = memberships;
    this.items = items;
    this.editLocks = editLocks;
    this.receipts = receipts;
    this.clock = clock;
  }

  /** Deletes only the account after ownership checks; retained shared data is never bulk-erased. */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void withdraw(UUID actor, JsonNode body) {
    CareMutationInput.object(body, "body", Set.of("clientRequestId"));
    UUID key = CareMutationInput.uuid(body.path("clientRequestId"), "clientRequestId", true);
    if (!receipts.tryLock(actor, key)) throw new ApiException(CommonErrorCode.REQUEST_IN_PROGRESS);
    // Provider identity -> account -> UUID-sorted spaces is the registration/deletion lock order.
    for (var identity : members.identities(actor))
      identities.lock(identity.provider(), identity.providerUserId());
    lifecycle.requireExisting(actor);
    if (receipts.find(actor, key).isPresent())
      throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    members.lockSpaces(actor);
    if (members.ownsSpace(actor)) throw new ApiException(UserErrorCode.OWNED_SPACE_REMAINS);
    var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    for (var membership : members.activeMemberships(actor)) {
      memberships.end(membership.space(), membership.id(), "WITHDRAWN", now);
      items.releaseAssignments(membership.space(), membership.id(), membership.id(), now);
      editLocks.releaseMembership(membership.space(), membership.id());
    }
    members.eraseAccount(actor);
    // No retained account tombstone or result receipt. Successful deletion ends authentication;
    // all subsequent retries with the old identity are 401, including after a fresh registration.
  }
}
