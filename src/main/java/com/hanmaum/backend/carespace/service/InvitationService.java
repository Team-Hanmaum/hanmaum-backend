package com.hanmaum.backend.carespace.service;

import com.hanmaum.backend.carespace.code.CareSpaceErrorCode;
import com.hanmaum.backend.carespace.dto.CareSpaceRole;
import com.hanmaum.backend.carespace.dto.CreateInvitationResponse;
import com.hanmaum.backend.carespace.dto.InvitationPreview;
import com.hanmaum.backend.carespace.dto.JoinInvitationResponse;
import com.hanmaum.backend.carespace.entity.Invitation;
import com.hanmaum.backend.carespace.repository.InvitationRepository;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.CommandDigest;
import com.hanmaum.backend.global.command.CommandReceipt;
import com.hanmaum.backend.global.command.CommandReceiptRepository;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiFieldError;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class InvitationService {
  private static final String ISSUE = "ISSUE_INVITATION";
  private static final String JOIN = "JOIN_INVITATION";
  private final InvitationRepository invitations;
  private final InvitationTokenService tokens;
  private final CareSpaceAccessService access;
  private final CommandReceiptRepository receipts;
  private final CommandDigest digests;
  private final Clock clock;

  public InvitationService(
      InvitationRepository invitations,
      InvitationTokenService tokens,
      CareSpaceAccessService access,
      CommandReceiptRepository receipts,
      CommandDigest digests,
      Clock clock) {
    this.invitations = invitations;
    this.tokens = tokens;
    this.access = access;
    this.receipts = receipts;
    this.digests = digests;
    this.clock = clock;
  }

  public CreateInvitationResponse issue(UUID userId, UUID spaceId, UUID requestId) {
    String scope = "SPACE:" + spaceId;
    var previous = begin(userId, requestId, ISSUE, scope);
    var issuer = access.requireActiveMember(userId, spaceId);
    if (previous.isPresent()) {
      var invitation =
          invitations
              .findById(UUID.fromString(previous.get().resultRefs().get("invitationId")))
              .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
      if (!issuer.membershipId().equals(invitation.issuedByMembershipId()))
        throw new ApiException(CommonErrorCode.FORBIDDEN);
      // Replaying an issuance may return an expired URL, but never renews its lifetime.
      return issuedResponse(invitation);
    }
    Instant now = now();
    UUID id = UUID.randomUUID();
    String token = tokens.generate();
    var invitation =
        new Invitation(
            id,
            spaceId,
            issuer.membershipId(),
            tokens.hash(token),
            tokens.sealUrl(id, token),
            now,
            now.plus(Duration.ofHours(24)));
    invitations.insert(invitation);
    save(
        userId,
        requestId,
        ISSUE,
        scope,
        Map.of(
            "invitationId",
            id.toString(),
            "spaceId",
            spaceId.toString(),
            "membershipId",
            issuer.membershipId().toString()),
        now);
    return issuedResponse(invitation);
  }

  public InvitationPreview preview(UUID userId, String token) {
    var invitation = find(tokens.hash(token));
    invitations.lockActiveSpace(invitation.spaceId());
    invitations.requireCurrentIssuer(invitation);
    requireUnexpired(invitation);
    boolean member = invitations.findCurrent(userId, invitation.spaceId()).isPresent();
    if (!member) requireNewInvitationAfterDeparture(userId, invitation);
    requireUnexpired(invitation);
    return new InvitationPreview(
        invitation.id(), invitation.spaceId(), invitation.expiresAt(), member);
  }

  public JoinResult join(UUID userId, String token, UUID requestId) {
    String hash = tokens.hash(token);
    String scope = "INVITATION_HASH:" + hash;
    var previous = begin(userId, requestId, JOIN, scope);
    if (previous.isPresent()) return replayJoin(userId, previous.get());

    var invitation = find(hash);
    UUID ownerMembershipId = invitations.lockActiveSpace(invitation.spaceId());
    invitations.requireCurrentIssuer(invitation);
    requireUnexpired(invitation);
    var current = invitations.findCurrent(userId, invitation.spaceId());
    boolean created = current.isEmpty();
    if (created) requireNewInvitationAfterDeparture(userId, invitation);
    Instant now = now();
    // A blocked row lock may have consumed the rest of the invitation lifetime.
    if (!now.isBefore(invitation.expiresAt()))
      throw new ApiException(CareSpaceErrorCode.INVITATION_EXPIRED);
    var participation = current.orElseGet(() -> invitations.join(userId, invitation, now));
    save(
        userId,
        requestId,
        JOIN,
        scope,
        Map.of(
            "spaceId",
            invitation.spaceId().toString(),
            "membershipId",
            participation.id().toString(),
            "membershipCreated",
            Boolean.toString(created)),
        now);
    return new JoinResult(
        created,
        new JoinInvitationResponse(
            invitation.spaceId(),
            participation.id(),
            participation.id().equals(ownerMembershipId)
                ? CareSpaceRole.OWNER
                : CareSpaceRole.MEMBER,
            participation.joinedAt()));
  }

  private JoinResult replayJoin(UUID userId, CommandReceipt receipt) {
    UUID spaceId = UUID.fromString(receipt.resultRefs().get("spaceId"));
    UUID membershipId = UUID.fromString(receipt.resultRefs().get("membershipId"));
    // The original successful join is independent of later invite expiry/issuer departure.
    // It still requires the SAME current participation; never restore an ended membership.
    try {
      var member = access.requireActiveMember(userId, spaceId);
      if (!member.membershipId().equals(membershipId))
        throw new ApiException(CommonErrorCode.FORBIDDEN);
      var participation =
          invitations
              .findCurrent(userId, spaceId)
              .orElseThrow(() -> new ApiException(CommonErrorCode.FORBIDDEN));
      return new JoinResult(
          Boolean.parseBoolean(receipt.resultRefs().get("membershipCreated")),
          new JoinInvitationResponse(
              spaceId, membershipId, member.role(), participation.joinedAt()));
    } catch (ApiException exception) {
      if (exception.code() == CommonErrorCode.RESOURCE_NOT_FOUND)
        throw new ApiException(CareSpaceErrorCode.INVITATION_UNAVAILABLE);
      throw exception;
    }
  }

  private Optional<CommandReceipt> begin(UUID userId, UUID requestId, String kind, String scope) {
    if (requestId == null)
      throw new ApiException(
          CommonErrorCode.INVALID_REQUEST,
          List.of(new ApiFieldError("clientRequestId", "요청 ID를 입력해주세요.")));
    if (!receipts.tryLock(userId, requestId))
      throw new ApiException(CommonErrorCode.REQUEST_IN_PROGRESS);
    var previous = receipts.find(userId, requestId);
    previous.ifPresent(
        receipt -> {
          if (!kind.equals(receipt.commandKind())
              || !scope.equals(receipt.scopeKey())
              || !digest(userId, requestId, kind, scope).equals(receipt.requestDigest()))
            throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        });
    return previous;
  }

  private void save(
      UUID userId,
      UUID requestId,
      String kind,
      String scope,
      Map<String, String> refs,
      Instant now) {
    receipts.saveSuccess(
        userId,
        requestId,
        new CommandReceipt(kind, scope, digest(userId, requestId, kind, scope), refs),
        now);
  }

  private String digest(UUID userId, UUID requestId, String kind, String scope) {
    return digests.digest(userId.toString(), requestId.toString(), kind, scope);
  }

  private Invitation find(String hash) {
    return invitations
        .findByHash(hash)
        .orElseThrow(() -> new ApiException(CareSpaceErrorCode.INVITATION_UNAVAILABLE));
  }

  private void requireUnexpired(Invitation invitation) {
    if (!now().isBefore(invitation.expiresAt()))
      throw new ApiException(CareSpaceErrorCode.INVITATION_EXPIRED);
  }

  private void requireNewInvitationAfterDeparture(UUID userId, Invitation invitation) {
    invitations
        .lastEndedAt(userId, invitation.spaceId())
        .ifPresent(
            ended -> {
              if (!invitation.issuedAt().isAfter(ended))
                throw new ApiException(CareSpaceErrorCode.INVITATION_UNAVAILABLE);
            });
  }

  private CreateInvitationResponse issuedResponse(Invitation invitation) {
    return new CreateInvitationResponse(
        invitation.id(),
        invitation.spaceId(),
        tokens.openUrl(invitation.id(), invitation.encryptedUrl()),
        invitation.issuedAt(),
        invitation.expiresAt());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  public record JoinResult(boolean created, JoinInvitationResponse response) {}
}
