package com.hanmaum.backend.carespace.service;

import com.hanmaum.backend.carespace.code.CareSpaceErrorCode;
import com.hanmaum.backend.carespace.dto.CreateCareSpaceRequest;
import com.hanmaum.backend.carespace.dto.CreateCareSpaceResponse;
import com.hanmaum.backend.carespace.entity.CareSpace;
import com.hanmaum.backend.carespace.entity.SpaceMembership;
import com.hanmaum.backend.carespace.repository.CareSpaceRepository;
import com.hanmaum.backend.carespace.repository.SpaceMembershipRepository;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.CommandDigest;
import com.hanmaum.backend.global.command.CommandReceipt;
import com.hanmaum.backend.global.command.CommandReceiptRepository;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiFieldError;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CareSpaceCreationService {
  private static final String KIND = "CREATE_CARE_SPACE";
  private static final String SCOPE = "SELF";
  private final CareSpaceRepository spaces;
  private final SpaceMembershipRepository memberships;
  private final CommandReceiptRepository receipts;
  private final CommandDigest digests;
  private final CareSpaceAccessService access;

  public CareSpaceCreationService(
      CareSpaceRepository spaces,
      SpaceMembershipRepository memberships,
      CommandReceiptRepository receipts,
      CommandDigest digests,
      CareSpaceAccessService access) {
    this.spaces = spaces;
    this.memberships = memberships;
    this.receipts = receipts;
    this.digests = digests;
    this.access = access;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public CreateCareSpaceResponse create(UUID userId, CreateCareSpaceRequest request) {
    String label = SubjectLabel.normalize(request.subjectLabel());
    UUID requestId = request.clientRequestId();
    if (requestId == null) {
      throw new ApiException(
          CommonErrorCode.INVALID_REQUEST,
          List.of(new ApiFieldError("clientRequestId", "요청 ID를 입력해주세요.")));
    }
    String digest = digests.digest(userId.toString(), requestId.toString(), KIND, SCOPE, label);
    if (!receipts.tryLock(userId, requestId))
      throw new ApiException(CommonErrorCode.REQUEST_IN_PROGRESS);
    var previous = receipts.find(userId, requestId);
    if (previous.isPresent()) {
      var receipt = previous.get();
      if (!KIND.equals(receipt.commandKind())
          || !SCOPE.equals(receipt.scopeKey())
          || !digest.equals(receipt.requestDigest())) {
        throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
      }
      UUID spaceId = UUID.fromString(receipt.resultRefs().get("spaceId"));
      UUID membershipId = UUID.fromString(receipt.resultRefs().get("membershipId"));
      var current = access.requireActiveMember(userId, spaceId);
      if (!current.membershipId().equals(membershipId))
        throw new ApiException(CommonErrorCode.FORBIDDEN);
      return new CreateCareSpaceResponse(spaceId, membershipId);
    }

    Instant now = Instant.now();
    UUID spaceId = UUID.randomUUID();
    UUID membershipId = UUID.randomUUID();
    if (!spaces.insertIfLabelAvailable(
        new CareSpace(spaceId, membershipId, userId, label, 1, "ACTIVE", now, now))) {
      throw new ApiException(
          CareSpaceErrorCode.SPACE_LABEL_DUPLICATED,
          List.of(new ApiFieldError("subjectLabel", "이미 소유한 공간과 다른 호칭을 입력해주세요.")));
    }
    memberships.insert(new SpaceMembership(membershipId, spaceId, userId, now, null, null));
    receipts.saveSuccess(
        userId,
        requestId,
        new CommandReceipt(
            KIND,
            SCOPE,
            digest,
            Map.of("spaceId", spaceId.toString(), "membershipId", membershipId.toString())),
        now);
    return new CreateCareSpaceResponse(spaceId, membershipId);
  }
}
