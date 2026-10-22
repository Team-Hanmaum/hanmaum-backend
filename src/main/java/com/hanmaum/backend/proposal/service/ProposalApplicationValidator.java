package com.hanmaum.backend.proposal.service;

import com.hanmaum.backend.careitem.repository.CareItemApplicationRepository;
import com.hanmaum.backend.careitem.service.*;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.proposal.code.ProposalErrorCode;
import com.hanmaum.backend.proposal.dto.*;
import com.hanmaum.backend.proposal.repository.ProposalDecisionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Shared validation for direct decisions, member submissions and owner request decisions. */
@Component
public class ProposalApplicationValidator {
  private final CareItemApplicationService applications;
  private final EditLockService leases;
  private final ProposalReviewToken reviews;
  private final ObjectMapper mapper;

  public ProposalApplicationValidator(
      CareItemApplicationService applications,
      EditLockService leases,
      ProposalReviewToken reviews,
      ObjectMapper mapper) {
    this.applications = applications;
    this.leases = leases;
    this.reviews = reviews;
    this.mapper = mapper;
  }

  public CareItemApplicationService.Prepared prepare(
      UUID space,
      UUID member,
      ProposalDecisionRepository.Proposal p,
      ProposalDecisionRequest.Entry e,
      CareItemApplicationRepository.Item target,
      String path) {
    if (!p.resolution().equals("OPEN") || p.body() == null) throw state();
    if (action(p.operation()) != e.action())
      throw CareItemInput.invalid(path + ".action", "제안 동작을 변경할 수 없습니다.");
    if (p.operation().equals("CREATE")) {
      if (e.targetItemId() != null || e.expectedItemVersion() != null || e.leaseToken() != null)
        throw CareItemInput.invalid(path, "생성에는 대상·버전·잠금 토큰을 전달하지 않습니다.");
    } else {
      if (target == null || e.expectedItemVersion() == null)
        throw CareItemInput.invalid(path, "대상과 버전을 입력해주세요.");
      if (!target.available()) throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
      if (target.version() != e.expectedItemVersion())
        throw new ApiException(CommonErrorCode.VERSION_CONFLICT);
      if (!target.type().equals(p.type()))
        throw CareItemInput.invalid(path + ".targetItemId", "동일 유형의 대상을 선택해주세요.");
      var body = mapper.readTree(p.body());
      boolean selected =
          body.path("targetItemId").isString()
              && body.path("targetItemId").asText().equalsIgnoreCase(target.id().toString());
      if (!body.path("targetItemId").isString())
        for (var candidate : body.path("candidateItemIds"))
          if (candidate.asText().equalsIgnoreCase(target.id().toString())) selected = true;
      if (!selected) throw CareItemInput.invalid(path + ".targetItemId", "제안에 포함된 대상을 선택해주세요.");
      leases.requireForWrite(space, target.id(), member, e.leaseToken());
    }
    if (!p.available()) throw state();
    return applications.prepare(
        space, p.type(), p.operation(), target, e.values(), path + ".values");
  }

  public void verifyReview(UUID actor, UUID space, UUID proposal, String supplied) {
    if (!matches(reviews.issue(actor, space, proposal), supplied)) throw state();
  }

  public static boolean matches(String expected, String supplied) {
    return expected != null
        && supplied != null
        && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            supplied.getBytes(StandardCharsets.US_ASCII));
  }

  public static ProposalAction action(String operation) {
    return switch (operation) {
      case "CREATE" -> ProposalAction.CREATE_ITEM;
      case "UPDATE" -> ProposalAction.UPDATE_ITEM;
      case "RESOLVE" -> ProposalAction.RESOLVE_QUESTION;
      case "LINK_EVIDENCE" -> ProposalAction.LINK_EVIDENCE;
      default -> throw new IllegalStateException("Unknown proposal operation");
    };
  }

  private ApiException state() {
    return new ApiException(ProposalErrorCode.PROPOSAL_STATE_CONFLICT);
  }
}
