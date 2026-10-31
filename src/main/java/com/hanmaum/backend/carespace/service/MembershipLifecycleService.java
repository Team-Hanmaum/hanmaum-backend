package com.hanmaum.backend.carespace.service;

import com.hanmaum.backend.careitem.service.CareItemApplicationService;
import com.hanmaum.backend.careitem.service.EditLockService;
import com.hanmaum.backend.carespace.code.CareSpaceErrorCode;
import com.hanmaum.backend.carespace.dto.CareSpaceAccess;
import com.hanmaum.backend.carespace.dto.CareSpaceRole;
import com.hanmaum.backend.carespace.dto.DepartureResponse;
import com.hanmaum.backend.carespace.dto.MembershipChangeRequest;
import com.hanmaum.backend.carespace.dto.RemoveMembershipResponse;
import com.hanmaum.backend.carespace.dto.TransferOwnershipRequest;
import com.hanmaum.backend.carespace.dto.TransferOwnershipResponse;
import com.hanmaum.backend.carespace.repository.MembershipLifecycleRepository;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.CommandDigest;
import com.hanmaum.backend.global.command.CommandReceipt;
import com.hanmaum.backend.global.command.CommandReceiptRepository;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiFieldError;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class MembershipLifecycleService {
  private static final String TRANSFER = "TRANSFER_OWNERSHIP";
  private static final String DEPART = "DEPART_SPACE";
  private static final String REMOVE = "REMOVE_MEMBERSHIP";
  private final CareSpaceAccessService access;
  private final MembershipLifecycleRepository memberships;
  private final CommandReceiptRepository receipts;
  private final CommandDigest digests;
  private final Clock clock;
  private final EditLockService editLocks;
  private final CareItemApplicationService items;

  public MembershipLifecycleService(
      CareSpaceAccessService access,
      MembershipLifecycleRepository memberships,
      CommandReceiptRepository receipts,
      CommandDigest digests,
      Clock clock,
      EditLockService editLocks,
      CareItemApplicationService items) {
    this.items = items;
    this.access = access;
    this.memberships = memberships;
    this.receipts = receipts;
    this.digests = digests;
    this.clock = clock;
    this.editLocks = editLocks;
  }

  public TransferOwnershipResponse transfer(
      UUID userId, UUID spaceId, TransferOwnershipRequest request) {
    long version = parseVersion(request.expectedSpaceVersion());
    UUID target = request.targetMembershipId();
    if (target == null) throw invalid("targetMembershipId", "이전할 구성원을 선택해주세요.");
    var command =
        command(userId, request.clientRequestId(), TRANSFER, spaceId, target.toString(), version);
    var previous = begin(command);
    if (previous.isPresent()) {
      var refs = previous.get().resultRefs();
      requireOriginalParticipation(userId, spaceId, refs, false);
      return transferResponse(spaceId, refs);
    }
    var actor = access.requireOwner(userId, spaceId);
    requireVersion(actor, version);
    var member = memberships.lockTarget(spaceId, target);
    if (!member.active() || target.equals(actor.membershipId())) throw stateConflict();
    Instant now = now();
    try {
      memberships.transfer(spaceId, target, member.userId(), now);
    } catch (DuplicateKeyException exception) {
      // This UPDATE changes only the owner/label unique key. Let the entire transaction roll back.
      throw new ApiException(CareSpaceErrorCode.SPACE_LABEL_DUPLICATED);
    }
    var refs =
        Map.of(
            "actorMembershipId",
            actor.membershipId().toString(),
            "ownerMembershipId",
            target.toString(),
            "spaceVersion",
            Long.toString(Math.incrementExact(version)));
    save(command, refs, now);
    return transferResponse(spaceId, refs);
  }

  public DepartureResponse depart(UUID userId, UUID spaceId, MembershipChangeRequest request) {
    long version = parseVersion(request.expectedSpaceVersion());
    var command = command(userId, request.clientRequestId(), DEPART, spaceId, "SELF", version);
    var previous = begin(command);
    // Explicit exception: own successful departure receipt conveys no current space data/authority.
    // Never resolve current membership here; it may be a NEW participation after rejoining.
    if (previous.isPresent()) return departureResponse(previous.get().resultRefs());
    var actor = access.requireActiveMember(userId, spaceId);
    if (actor.role() == CareSpaceRole.OWNER)
      throw new ApiException(CareSpaceErrorCode.OWNER_TRANSFER_REQUIRED);
    requireVersion(actor, version);
    Instant now = now();
    memberships.end(spaceId, actor.membershipId(), "LEFT", now);
    items.releaseAssignments(spaceId, actor.membershipId(), actor.membershipId(), now);
    editLocks.releaseMembership(spaceId, actor.membershipId());
    var refs = Map.of("membershipId", actor.membershipId().toString(), "endedAt", now.toString());
    save(command, refs, now);
    return departureResponse(refs);
  }

  public RemoveMembershipResponse remove(
      UUID userId, UUID spaceId, UUID target, MembershipChangeRequest request) {
    long version = parseVersion(request.expectedSpaceVersion());
    var command =
        command(userId, request.clientRequestId(), REMOVE, spaceId, target.toString(), version);
    var previous = begin(command);
    if (previous.isPresent()) {
      var refs = previous.get().resultRefs();
      requireOriginalParticipation(userId, spaceId, refs, true);
      return removalResponse(refs);
    }
    var actor = access.requireOwner(userId, spaceId);
    requireVersion(actor, version);
    var member = memberships.lockTarget(spaceId, target);
    if (!member.active() || target.equals(actor.membershipId())) throw stateConflict();
    Instant now = now();
    memberships.end(spaceId, target, "REMOVED", now);
    items.releaseAssignments(spaceId, target, actor.membershipId(), now);
    editLocks.releaseMembership(spaceId, target);
    var refs =
        Map.of(
            "actorMembershipId",
            actor.membershipId().toString(),
            "membershipId",
            target.toString(),
            "endedAt",
            now.toString(),
            "spaceVersion",
            Long.toString(Math.incrementExact(version)));
    save(command, refs, now);
    return removalResponse(refs);
  }

  private void requireOriginalParticipation(
      UUID userId, UUID spaceId, Map<String, String> refs, boolean owner) {
    var current =
        owner ? access.requireOwner(userId, spaceId) : access.requireActiveMember(userId, spaceId);
    if (!current.membershipId().toString().equals(refs.get("actorMembershipId")))
      throw new ApiException(CommonErrorCode.FORBIDDEN);
  }

  private static void requireVersion(CareSpaceAccess actor, long expected) {
    if (actor.version() != expected) throw new ApiException(CommonErrorCode.VERSION_CONFLICT);
  }

  private static long parseVersion(String value) {
    if (value != null && value.matches("[1-9][0-9]{0,18}")) {
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException ignored) {
      }
    }
    throw invalid("expectedSpaceVersion", "1~9223372036854775807의 십진 정수 문자열을 입력해주세요.");
  }

  private Command command(
      UUID actor, UUID id, String kind, UUID space, String target, long version) {
    if (id == null) throw invalid("clientRequestId", "요청 ID를 입력해주세요.");
    String scope = "SPACE:" + space;
    return new Command(
        actor,
        id,
        kind,
        scope,
        digests.digest(
            actor.toString(), id.toString(), kind, scope, target, Long.toString(version)));
  }

  private Optional<CommandReceipt> begin(Command command) {
    if (!receipts.tryLock(command.actor(), command.id()))
      throw new ApiException(CommonErrorCode.REQUEST_IN_PROGRESS);
    var previous = receipts.find(command.actor(), command.id());
    previous.ifPresent(
        receipt -> {
          if (!command.kind().equals(receipt.commandKind())
              || !command.scope().equals(receipt.scopeKey())
              || !command.digest().equals(receipt.requestDigest()))
            throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        });
    return previous;
  }

  private void save(Command command, Map<String, String> refs, Instant now) {
    receipts.saveSuccess(
        command.actor(),
        command.id(),
        new CommandReceipt(command.kind(), command.scope(), command.digest(), refs),
        now);
  }

  private static TransferOwnershipResponse transferResponse(
      UUID spaceId, Map<String, String> refs) {
    return new TransferOwnershipResponse(
        spaceId,
        UUID.fromString(refs.get("ownerMembershipId")),
        CareSpaceRole.MEMBER,
        refs.get("spaceVersion"));
  }

  private static DepartureResponse departureResponse(Map<String, String> refs) {
    return new DepartureResponse(
        UUID.fromString(refs.get("membershipId")), Instant.parse(refs.get("endedAt")));
  }

  private static RemoveMembershipResponse removalResponse(Map<String, String> refs) {
    return new RemoveMembershipResponse(
        UUID.fromString(refs.get("membershipId")),
        Instant.parse(refs.get("endedAt")),
        refs.get("spaceVersion"));
  }

  private static ApiException invalid(String field, String reason) {
    return new ApiException(
        CommonErrorCode.INVALID_REQUEST, List.of(new ApiFieldError(field, reason)));
  }

  private static ApiException stateConflict() {
    return new ApiException(CareSpaceErrorCode.MEMBERSHIP_STATE_CONFLICT);
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private record Command(UUID actor, UUID id, String kind, String scope, String digest) {}
}
