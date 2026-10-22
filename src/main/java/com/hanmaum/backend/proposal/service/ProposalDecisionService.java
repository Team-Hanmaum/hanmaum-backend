package com.hanmaum.backend.proposal.service;

import com.hanmaum.backend.careitem.repository.CareItemApplicationRepository;
import com.hanmaum.backend.careitem.service.*;
import com.hanmaum.backend.carespace.service.CareSpaceAccessService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.*;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiFieldError;
import com.hanmaum.backend.proposal.code.ProposalErrorCode;
import com.hanmaum.backend.proposal.dto.*;
import com.hanmaum.backend.proposal.repository.ProposalDecisionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProposalDecisionService {
  private final CareSpaceAccessService access;
  private final CommandReceiptRepository receipts;
  private final CommandDigest digests;
  private final ProposalDecisionRepository proposals;
  private final CareItemApplicationService applications;
  private final EditLockService leases;
  private final ProposalReviewToken reviews;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ProposalDecisionService(
      CareSpaceAccessService access,
      CommandReceiptRepository receipts,
      CommandDigest digests,
      ProposalDecisionRepository proposals,
      CareItemApplicationService applications,
      EditLockService leases,
      ProposalReviewToken reviews,
      ObjectMapper mapper,
      Clock clock) {
    this.access = access;
    this.receipts = receipts;
    this.digests = digests;
    this.proposals = proposals;
    this.applications = applications;
    this.leases = leases;
    this.reviews = reviews;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional
  public ProposalApplicationResponse apply(UUID actor, UUID space, JsonNode input) {
    validateSize(input);
    return proposals.application(
        space, decide(actor, space, ProposalDecisionRequest.parse(input, true), true));
  }

  @Transactional
  public ProposalDeclineResponse decline(UUID actor, UUID space, JsonNode input) {
    validateSize(input);
    return proposals.decline(
        space, decide(actor, space, ProposalDecisionRequest.parse(input, false), false));
  }

  private UUID decide(UUID actor, UUID space, ProposalDecisionRequest request, boolean apply) {
    String kind = apply ? "APPLY_PROPOSALS" : "DECLINE_PROPOSALS", scope = "SPACE:" + space;
    var logical = mapper.createArrayNode();
    for (var entry : request.entries()) {
      var e = mapper.createObjectNode();
      e.put("proposalId", entry.proposalId().toString());
      e.put("action", entry.action() == null ? null : entry.action().name());
      e.put("target", entry.targetItemId() == null ? null : entry.targetItemId().toString());
      e.put("version", entry.expectedItemVersion());
      e.set("values", entry.values() == null ? mapper.nullNode() : canonical(entry.values()));
      logical.add(e);
    }
    String digest =
        digests.digest(kind, scope, actor.toString(), mapper.writeValueAsString(logical));
    if (!receipts.tryLock(actor, request.clientRequestId()))
      throw new ApiException(CommonErrorCode.REQUEST_IN_PROGRESS);
    var previous = receipts.find(actor, request.clientRequestId());
    if (previous.isPresent()
        && (!kind.equals(previous.get().commandKind())
            || !scope.equals(previous.get().scopeKey())
            || !digest.equals(previous.get().requestDigest())))
      throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    UUID member = access.requireOwner(actor, space).membershipId();
    if (previous.isPresent()) {
      if (!member.toString().equals(previous.get().resultRefs().get("membershipId")))
        throw new ApiException(CommonErrorCode.FORBIDDEN);
      // Replay returns immutable IDs/versions only, including after content redaction; no old text.
      return UUID.fromString(previous.get().resultRefs().get("batchId"));
    }
    Map<UUID, ProposalDecisionRepository.Proposal> loaded = new HashMap<>();
    request.entries().stream()
        .map(ProposalDecisionRequest.Entry::proposalId)
        .sorted()
        .forEach(
            id ->
                loaded.put(
                    id,
                    proposals
                        .lock(space, id)
                        .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND))));
    Set<UUID> targets = new HashSet<>();
    for (var e : request.entries())
      if (e.targetItemId() != null && !targets.add(e.targetItemId()))
        throw new ApiException(
            ProposalErrorCode.PROPOSAL_BATCH_CONFLICT,
            List.of(new ApiFieldError("entries", "같은 항목의 제안은 하나만 선택해주세요.")));
    Map<UUID, CareItemApplicationRepository.Item> locked = new HashMap<>();
    targets.stream().sorted().forEach(id -> locked.put(id, applications.lock(space, id)));
    List<Plan> plans = new ArrayList<>();
    int documentBytes = 0;
    for (int i = 0; i < request.entries().size(); i++) {
      var e = request.entries().get(i);
      var p = loaded.get(e.proposalId());
      String path = "entries[" + i + "]";
      if (!p.resolution().equals("OPEN")) throw state();
      if (p.body() == null) throw state();
      CareItemApplicationService.Prepared prepared = null;
      if (apply) {
        if (!publicAction(p.operation()).equals(e.action()))
          throw CareItemInput.invalid(path + ".action", "제안 동작을 변경할 수 없습니다.");
        boolean create = p.operation().equals("CREATE");
        var target = e.targetItemId() == null ? null : locked.get(e.targetItemId());
        if (create) {
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
          JsonNode body = mapper.readTree(p.body());
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
        prepared =
            applications.prepare(
                space, p.type(), p.operation(), target, e.values(), path + ".values");
        if (!p.operation().equals("LINK_EVIDENCE")) {
          CareMutationSizeLimits.strings(prepared.body(), path + ".values");
          documentBytes +=
              CareMutationSizeLimits.bytes(
                  mapper,
                  prepared.body(),
                  CareMutationSizeLimits.DOCUMENT_BYTES - documentBytes,
                  path + ".values");
        }
      }
      String current = reviews.issue(actor, space, p.id());
      if (!p.available()
          || current == null
          || !MessageDigest.isEqual(
              current.getBytes(StandardCharsets.US_ASCII),
              e.reviewToken().getBytes(StandardCharsets.US_ASCII))) throw state();
      plans.add(new Plan(p, prepared));
    }
    // No writes before this boundary; all proposals see the same pre-mutation state.
    UUID batch = UUID.randomUUID();
    var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    proposals.batch(batch, space, member, apply, now);
    for (int i = 0; i < plans.size(); i++) {
      var plan = plans.get(i);
      var p = plan.proposal();
      if (apply) {
        var result =
            applications.apply(
                space, member, plan.prepared(), p.document(), p.evidence(), p.run(), now);
        proposals.decision(
            space, batch, i, p.id(), result.item(), result.version(), result.document());
        if (!p.operation().equals("CREATE")) leases.releaseAfterSave(space, result.item());
      } else proposals.decision(space, batch, i, p.id(), null, null, null);
    }
    receipts.saveSuccess(
        actor,
        request.clientRequestId(),
        new CommandReceipt(
            kind,
            scope,
            digest,
            Map.of("batchId", batch.toString(), "membershipId", member.toString())),
        now);
    return batch;
  }

  private void validateSize(JsonNode input) {
    // Also defend direct service callers before canonicalization, hashing or DB writes.
    CareMutationSizeLimits.strings(input, "body");
    CareMutationSizeLimits.bytes(mapper, input, CareMutationSizeLimits.REQUEST_BYTES, "body");
  }

  private JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      var out = mapper.createObjectNode();
      node.propertyNames().stream().sorted().forEach(k -> out.set(k, canonical(node.get(k))));
      return out;
    }
    if (node.isArray()) {
      var out = mapper.createArrayNode();
      node.forEach(n -> out.add(canonical(n)));
      return out;
    }
    return node;
  }

  private ProposalAction publicAction(String operation) {
    return switch (operation) {
      case "CREATE" -> ProposalAction.CREATE_ITEM;
      case "UPDATE" -> ProposalAction.UPDATE_ITEM;
      case "RESOLVE" -> ProposalAction.RESOLVE_QUESTION;
      case "LINK_EVIDENCE" -> ProposalAction.LINK_EVIDENCE;
      default -> throw new IllegalStateException("Unknown operation");
    };
  }

  private ApiException state() {
    return new ApiException(ProposalErrorCode.PROPOSAL_STATE_CONFLICT);
  }

  private record Plan(
      ProposalDecisionRepository.Proposal proposal, CareItemApplicationService.Prepared prepared) {}
}
