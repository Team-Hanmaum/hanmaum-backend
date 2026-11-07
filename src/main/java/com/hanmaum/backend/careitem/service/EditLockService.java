package com.hanmaum.backend.careitem.service;

import com.hanmaum.backend.careitem.code.CareItemErrorCode;
import com.hanmaum.backend.careitem.dto.EditLockPurpose;
import com.hanmaum.backend.careitem.dto.EditLockResponse;
import com.hanmaum.backend.careitem.repository.EditLockRepository;
import com.hanmaum.backend.carespace.dto.CareSpaceRole;
import com.hanmaum.backend.carespace.service.CareSpaceAccessService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.CommandDigest;
import com.hanmaum.backend.global.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EditLockService {
  private final EditLockRepository locks;
  private final CareSpaceAccessService access;
  private final CommandDigest digests;
  private final Duration duration, renewal;
  private final SecureRandom random = new SecureRandom();

  public EditLockService(
      EditLockRepository locks,
      CareSpaceAccessService access,
      CommandDigest digests,
      @Value("${app.care-items.edit-lock-duration:PT2M}") Duration duration,
      @Value("${app.care-items.edit-lock-renewal:PT30S}") Duration renewal) {
    if (duration.isNegative()
        || duration.isZero()
        || renewal.toSeconds() < 1
        || renewal.compareTo(duration) >= 0)
      throw new IllegalArgumentException(
          "Edit lock renewal must be positive and shorter than duration");
    this.locks = locks;
    this.access = access;
    this.digests = digests;
    this.duration = duration;
    this.renewal = renewal;
  }

  /** Invoked by membership termination under the existing space lock and transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseMembership(UUID space, UUID member) {
    locks.releaseMembership(space, member);
  }

  @Transactional
  public EditLockResponse acquire(UUID actor, UUID space, UUID item, long expectedVersion) {
    return acquire(actor, space, item, expectedVersion, EditLockPurpose.EDIT);
  }

  @Transactional
  public EditLockResponse acquire(
      UUID actor, UUID space, UUID item, long expectedVersion, EditLockPurpose purpose) {
    var current = access.requireActiveMember(actor, space);
    requireRole(current.role(), purpose);
    var member = current.membershipId();
    var state = requireItem(space, item, purpose);
    if (state.version() != expectedVersion)
      throw new ApiException(CommonErrorCode.VERSION_CONFLICT);
    Instant now = now();
    if (locks.live(space, item, now).isPresent())
      throw new ApiException(CareItemErrorCode.ITEM_LOCKED);
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expiry = now.plus(duration);
    locks.save(space, item, member, digest(space, item, member, token), expiry, purpose);
    return new EditLockResponse(item, token, member, expiry, renewal.toSeconds());
  }

  @Transactional
  public EditLockResponse renew(UUID actor, UUID space, UUID item, String token) {
    return renew(actor, space, item, token, EditLockPurpose.EDIT);
  }

  @Transactional
  public EditLockResponse renew(
      UUID actor, UUID space, UUID item, String token, EditLockPurpose purpose) {
    var current = access.requireActiveMember(actor, space);
    requireRole(current.role(), purpose);
    UUID member = current.membershipId();
    requireItem(space, item, purpose);
    var lease = requireLease(space, item, member, token);
    if (lease.purpose() != purpose) throw new ApiException(CareItemErrorCode.EDIT_LOCK_EXPIRED);
    Instant expiry = now().plus(duration);
    locks.save(space, item, member, lease.digest(), expiry, lease.purpose());
    return new EditLockResponse(item, null, member, expiry, renewal.toSeconds());
  }

  @Transactional
  public void release(UUID actor, UUID space, UUID item, String token) {
    UUID member = access.requireActiveMember(actor, space).membershipId();
    if (locks.lockItem(space, item).isEmpty())
      throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
    if (locks.live(space, item, now()).isEmpty()) {
      locks.release(space, item);
      return;
    }
    requireLease(space, item, member, token);
    locks.release(space, item);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requireForWrite(UUID space, UUID item, UUID member, String token) {
    requireForWrite(space, item, member, token, EditLockPurpose.EDIT);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requireForWrite(
      UUID space, UUID item, UUID member, String token, EditLockPurpose purpose) {
    requireItem(space, item, purpose);
    var live = locks.live(space, item, now());
    if (live.isPresent() && !live.get().member().equals(member))
      throw new ApiException(CareItemErrorCode.ITEM_LOCKED);
    if (requireLease(space, item, member, token).purpose() != purpose)
      throw new ApiException(CareItemErrorCode.EDIT_LOCK_EXPIRED);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseAfterSave(UUID space, UUID item) {
    locks.release(space, item);
  }

  /** Deletion never restores content and may also target an item awaiting reconfirmation. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requireForDeletion(UUID space, UUID item, UUID member, String token) {
    var state = lockItem(space, item);
    if (!state.visible()) throw new ApiException(CareItemErrorCode.ITEM_STATE_CONFLICT);
    var live = locks.live(space, item, now());
    if (live.isPresent() && !live.get().member().equals(member))
      throw new ApiException(CareItemErrorCode.ITEM_LOCKED);
    var lease = requireLease(space, item, member, token);
    if (lease.purpose() != EditLockPurpose.DELETION
        && (lease.purpose() != EditLockPurpose.EDIT || state.needsReconfirmation()))
      throw new ApiException(CareItemErrorCode.EDIT_LOCK_EXPIRED);
  }

  private EditLockRepository.Item requireItem(UUID space, UUID item, EditLockPurpose purpose) {
    var state = lockItem(space, item);
    requireState(state, purpose);
    return state;
  }

  private EditLockRepository.Item lockItem(UUID space, UUID item) {
    return locks
        .lockItem(space, item)
        .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
  }

  private void requireState(EditLockRepository.Item state, EditLockPurpose purpose) {
    if (!state.visible() || (purpose == EditLockPurpose.EDIT && state.needsReconfirmation()))
      throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
    if (purpose == EditLockPurpose.RECONFIRMATION && !state.needsReconfirmation())
      throw new ApiException(CareItemErrorCode.ITEM_STATE_CONFLICT);
  }

  private void requireRole(CareSpaceRole role, EditLockPurpose purpose) {
    if (purpose == EditLockPurpose.RECONFIRMATION && role != CareSpaceRole.OWNER)
      throw new ApiException(CommonErrorCode.FORBIDDEN);
  }

  private EditLockRepository.Lease requireLease(UUID space, UUID item, UUID member, String token) {
    var lease =
        locks
            .live(space, item, now())
            .orElseThrow(() -> new ApiException(CareItemErrorCode.EDIT_LOCK_EXPIRED));
    if (!lease.member().equals(member)) throw new ApiException(CommonErrorCode.FORBIDDEN);
    if (token == null
        || !token.matches("[A-Za-z0-9_-]{43}")
        || !MessageDigest.isEqual(
            lease.digest().getBytes(StandardCharsets.US_ASCII),
            digest(space, item, member, token).getBytes(StandardCharsets.US_ASCII)))
      throw new ApiException(CareItemErrorCode.EDIT_LOCK_EXPIRED);
    return lease;
  }

  private String digest(UUID space, UUID item, UUID member, String token) {
    return digests.digest(
        "CARE_ITEM_LEASE", space.toString(), item.toString(), member.toString(), token);
  }

  private Instant now() {
    // Read after acquiring the item/space locks, not at transaction start or on an app node.
    return locks.now();
  }
}
