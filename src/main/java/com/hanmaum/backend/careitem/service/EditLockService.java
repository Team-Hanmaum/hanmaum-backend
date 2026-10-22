package com.hanmaum.backend.careitem.service;

import com.hanmaum.backend.careitem.code.CareItemErrorCode;
import com.hanmaum.backend.careitem.dto.EditLockResponse;
import com.hanmaum.backend.careitem.repository.EditLockRepository;
import com.hanmaum.backend.carespace.service.CareSpaceAccessService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.CommandDigest;
import com.hanmaum.backend.global.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
  private final Clock clock;
  private final Duration duration, renewal;
  private final SecureRandom random = new SecureRandom();

  public EditLockService(
      EditLockRepository locks,
      CareSpaceAccessService access,
      CommandDigest digests,
      Clock clock,
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
    this.clock = clock;
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
    var member = access.requireActiveMember(actor, space).membershipId();
    var state = requireItem(space, item);
    if (state.version() != expectedVersion)
      throw new ApiException(CommonErrorCode.VERSION_CONFLICT);
    Instant now = now();
    if (locks.live(space, item, now).isPresent())
      throw new ApiException(CareItemErrorCode.ITEM_LOCKED);
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expiry = now.plus(duration);
    locks.save(space, item, member, digest(space, item, member, token), expiry);
    return new EditLockResponse(item, token, member, expiry, renewal.toSeconds());
  }

  @Transactional
  public EditLockResponse renew(UUID actor, UUID space, UUID item, String token) {
    UUID member = access.requireActiveMember(actor, space).membershipId();
    requireItem(space, item);
    var lease = requireLease(space, item, member, token);
    Instant expiry = now().plus(duration);
    locks.save(space, item, member, lease.digest(), expiry);
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
    requireItem(space, item);
    var live = locks.live(space, item, now());
    if (live.isPresent() && !live.get().member().equals(member))
      throw new ApiException(CareItemErrorCode.ITEM_LOCKED);
    requireLease(space, item, member, token);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseAfterSave(UUID space, UUID item) {
    locks.release(space, item);
  }

  private EditLockRepository.Item requireItem(UUID space, UUID item) {
    var state =
        locks
            .lockItem(space, item)
            .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    if (!state.available()) throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
    return state;
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
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
