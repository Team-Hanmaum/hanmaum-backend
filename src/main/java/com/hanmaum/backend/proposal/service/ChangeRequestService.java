package com.hanmaum.backend.proposal.service;

import com.hanmaum.backend.careitem.repository.CareItemApplicationRepository;
import com.hanmaum.backend.careitem.service.*;
import com.hanmaum.backend.carespace.dto.*;
import com.hanmaum.backend.carespace.service.CareSpaceAccessService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.*;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.proposal.code.ProposalErrorCode;
import com.hanmaum.backend.proposal.dto.*;
import com.hanmaum.backend.proposal.repository.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Member requests preserve intent; only an owner decision mutates actual care items. */
@Service
@Transactional
public class ChangeRequestService {
  private final CareSpaceAccessService access;
  private final ChangeRequestRepository requests;
  private final ProposalDecisionRepository proposals;
  private final CareItemApplicationService applications;
  private final ProposalApplicationValidator validator;
  private final EditLockService leases;
  private final ChangeRequestReviewToken reviews;
  private final CommandReceiptRepository receipts;
  private final CommandDigest digests;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ChangeRequestService(
      CareSpaceAccessService access,
      ChangeRequestRepository requests,
      ProposalDecisionRepository proposals,
      CareItemApplicationService applications,
      ProposalApplicationValidator validator,
      EditLockService leases,
      ChangeRequestReviewToken reviews,
      CommandReceiptRepository receipts,
      CommandDigest digests,
      ObjectMapper mapper,
      Clock clock) {
    this.access = access;
    this.requests = requests;
    this.proposals = proposals;
    this.applications = applications;
    this.validator = validator;
    this.leases = leases;
    this.reviews = reviews;
    this.receipts = receipts;
    this.digests = digests;
    this.mapper = mapper;
    this.clock = clock;
  }

  public JsonNode submit(UUID actor, UUID space, JsonNode input) {
    validateSize(input);
    var request = ProposalDecisionRequest.parse(input, true);
    var logical = mapper.createArrayNode();
    for (var e : request.entries()) {
      var l =
          logical
              .addObject()
              .put("proposalId", e.proposalId().toString())
              .put("action", e.action().name());
      l.put("targetItemId", text(e.targetItemId()));
      l.put("expectedItemVersion", e.expectedItemVersion());
      l.set("values", e.values() == null ? mapper.nullNode() : e.values());
    }
    var command =
        begin(actor, space, request.clientRequestId(), "SUBMIT_CHANGE_REQUEST", null, logical);
    var current = access.requireActiveMember(actor, space);
    if (current.role() != CareSpaceRole.MEMBER) throw forbidden();
    if (command.previous() != null)
      return requests.submissionResult(space, replay(command, current.membershipId()));
    var loaded =
        loadProposals(
            space,
            request.entries().stream().map(ProposalDecisionRequest.Entry::proposalId).toList());
    var locked =
        lockTargets(
            space,
            request.entries().stream().map(ProposalDecisionRequest.Entry::targetItemId).toList());
    int documentBytes = 0;
    for (int i = 0; i < request.entries().size(); i++) {
      var e = request.entries().get(i);
      var p = loaded.get(e.proposalId());
      String path = "entries[" + i + "]";
      validator.prepare(space, current.membershipId(), p, e, locked.get(e.targetItemId()), path);
      validator.verifyReview(actor, space, p.id(), e.reviewToken());
      if (requests.hasPending(space, current.membershipId(), p.id()))
        throw new ApiException(ProposalErrorCode.CHANGE_REQUEST_ALREADY_PENDING);
      var document = mapper.createObjectNode();
      document.set("values", e.values() == null ? mapper.nullNode() : e.values());
      documentBytes +=
          CareMutationSizeLimits.bytes(
              mapper,
              document,
              CareMutationSizeLimits.DOCUMENT_BYTES - documentBytes,
              path + ".values");
    }
    // Every entry is checked against the unchanged state before submission documents are written.
    UUID id = UUID.randomUUID();
    var now = now();
    requests.createRequest(space, id, current.membershipId(), now);
    for (int i = 0; i < request.entries().size(); i++) {
      var e = request.entries().get(i);
      var p = loaded.get(e.proposalId());
      var target = locked.get(e.targetItemId());
      UUID doc =
          requests.document(
              space,
              e.values(),
              p.document(),
              p.run(),
              target == null ? null : target.document(),
              now);
      requests.insertEntry(space, id, UUID.randomUUID(), i, e, doc);
      if (target != null) leases.releaseAfterSave(space, target.id());
    }
    save(command, current.membershipId(), id, now);
    return requests.submissionResult(space, id);
  }

  public JsonNode apply(UUID actor, UUID space, JsonNode input) {
    return decide(actor, space, null, input, "APPLIED");
  }

  public JsonNode decline(UUID actor, UUID space, JsonNode input) {
    return decide(actor, space, null, input, "DECLINED");
  }

  public JsonNode cancel(UUID actor, UUID space, UUID requestId, JsonNode input) {
    return decide(actor, space, requestId, input, "CANCELED");
  }

  private JsonNode decide(UUID actor, UUID space, UUID requestId, JsonNode input, String decision) {
    validateSize(input);
    boolean apply = decision.equals("APPLIED"), cancel = decision.equals("CANCELED");
    var selection = parseSelection(input, apply, cancel);
    var logical = mapper.createArrayNode();
    for (var e : selection.entries()) {
      var l = logical.addObject().put("requestEntryId", e.id().toString());
      if (apply) {
        l.put("expectedItemVersion", e.version());
        // Omitted override is not equivalent to an explicit null override.
        if (e.values() != null) l.set("values", e.values());
      }
    }
    var command =
        begin(
            actor, space, selection.key(), "DECIDE_CHANGE_REQUEST_" + decision, requestId, logical);
    var current =
        cancel ? access.requireActiveMember(actor, space) : access.requireOwner(actor, space);
    if (command.previous() != null)
      return requests.decisionResult(space, replay(command, current.membershipId()));
    if (cancel) {
      var header = requests.header(space, requestId).orElseThrow(this::missing);
      if (!header.memberId().equals(current.membershipId())) throw forbidden();
    }
    Map<UUID, ChangeRequestRepository.Entry> entries = new HashMap<>();
    selection.entries().stream()
        .map(SelectionEntry::id)
        .sorted()
        .forEach(id -> entries.put(id, requests.lock(space, id).orElseThrow(this::missing)));
    for (var e : entries.values()) {
      if (cancel && !e.requestId().equals(requestId)) throw missing();
      if (cancel && !e.memberId().equals(current.membershipId())) throw forbidden();
      if (!e.status().equals("PENDING")) {
        if (apply && "TARGET_VERSION_CHANGED".equals(e.invalidReason()))
          throw new ApiException(CommonErrorCode.VERSION_CONFLICT);
        throw state();
      }
    }
    Map<UUID, ProposalDecisionRepository.Proposal> loaded = Map.of();
    Map<UUID, CareItemApplicationRepository.Item> locked = Map.of();
    if (apply) {
      var proposalIds =
          selection.entries().stream().map(e -> entries.get(e.id()).proposalId()).toList();
      if (new HashSet<>(proposalIds).size() != proposalIds.size()) throw batchConflict();
      loaded = loadProposals(space, proposalIds);
      locked =
          lockTargets(
              space,
              selection.entries().stream().map(e -> entries.get(e.id()).targetItemId()).toList());
    }
    List<DecisionPlan> plans = new ArrayList<>();
    int documentBytes = 0;
    for (int i = 0; i < selection.entries().size(); i++) {
      var chosen = selection.entries().get(i);
      var e = entries.get(chosen.id());
      if (!cancel
          && !ProposalApplicationValidator.matches(reviews.issue(actor, space, e), chosen.token()))
        throw state();
      CareItemApplicationService.Prepared prepared = null;
      if (apply) {
        if (!Objects.equals(chosen.version(), e.expectedItemVersion())) {
          if (e.expectedItemVersion() != null && chosen.version() == null)
            throw CareItemInput.invalid(
                "entries[" + i + "].expectedItemVersion", "검토한 항목 버전을 입력해주세요.");
          if (e.expectedItemVersion() == null)
            throw CareItemInput.invalid("entries[" + i + "]", "생성 요청에는 버전을 전달하지 않습니다.");
          throw new ApiException(CommonErrorCode.VERSION_CONFLICT);
        }
        var values = chosen.values() == null ? mapper.readTree(e.values()) : chosen.values();
        var proposal = loaded.get(e.proposalId());
        var operation =
            new ProposalDecisionRequest.Entry(
                e.proposalId(),
                ProposalAction.valueOf(e.action()),
                null,
                e.targetItemId(),
                e.expectedItemVersion(),
                chosen.lease(),
                values);
        prepared =
            validator.prepare(
                space,
                current.membershipId(),
                proposal,
                operation,
                locked.get(e.targetItemId()),
                "entries[" + i + "]");
        if (!e.action().equals("LINK_EVIDENCE")) {
          CareMutationSizeLimits.strings(prepared.body(), "entries[" + i + "].values");
          documentBytes +=
              CareMutationSizeLimits.bytes(
                  mapper,
                  prepared.body(),
                  CareMutationSizeLimits.DOCUMENT_BYTES - documentBytes,
                  "entries[" + i + "].values");
        }
      }
      plans.add(new DecisionPlan(e, prepared));
    }
    UUID batch = UUID.randomUUID();
    var now = now();
    requests.batch(space, batch, current.membershipId(), decision, now);
    if (apply) proposals.batch(batch, space, current.membershipId(), true, now);
    for (int i = 0; i < plans.size(); i++) {
      var plan = plans.get(i);
      var e = plan.entry();
      if (apply) {
        var p = loaded.get(e.proposalId());
        var result =
            applications.apply(
                space,
                current.membershipId(),
                plan.prepared(),
                p.document(),
                p.evidence(),
                p.run(),
                now);
        // LINK reuses the old document; linking it to its own descendant would create a cycle.
        if (!e.action().equals("LINK_EVIDENCE"))
          requests.linkAppliedDocument(space, result.document(), e.documentId());
        proposals.decision(
            space, batch, i, p.id(), result.item(), result.version(), result.document());
        // Keep the item document reference for history; LINK has no applied values to expose.
        requests.decide(
            space, e.id(), batch, decision, result.item(), result.version(), result.document());
        if (e.targetItemId() != null) leases.releaseAfterSave(space, e.targetItemId());
      } else requests.decide(space, e.id(), batch, decision, null, null, null);
    }
    save(command, current.membershipId(), batch, now);
    return requests.decisionResult(space, batch);
  }

  private Map<UUID, ProposalDecisionRepository.Proposal> loadProposals(UUID space, List<UUID> ids) {
    Map<UUID, ProposalDecisionRepository.Proposal> out = new HashMap<>();
    ids.stream()
        .sorted()
        .forEach(id -> out.put(id, proposals.lock(space, id).orElseThrow(this::missing)));
    return out;
  }

  private Map<UUID, CareItemApplicationRepository.Item> lockTargets(UUID space, List<UUID> ids) {
    Set<UUID> targets = new HashSet<>();
    for (UUID id : ids) if (id != null && !targets.add(id)) throw batchConflict();
    Map<UUID, CareItemApplicationRepository.Item> out = new HashMap<>();
    targets.stream().sorted().forEach(id -> out.put(id, applications.lock(space, id)));
    return out;
  }

  private Selection parseSelection(JsonNode input, boolean apply, boolean cancel) {
    CareMutationInput.object(input, "body", Set.of("clientRequestId", "entries"));
    UUID key = CareMutationInput.uuid(input.path("clientRequestId"), "clientRequestId", true);
    var array = input.path("entries");
    if (!array.isArray() || array.isEmpty() || array.size() > 20)
      throw CareItemInput.invalid("entries", "요청을 1개 이상 20개 이하로 선택해주세요.");
    List<SelectionEntry> result = new ArrayList<>();
    Set<UUID> ids = new HashSet<>();
    for (int i = 0; i < array.size(); i++) {
      var e = array.get(i);
      String path = "entries[" + i + "]";
      CareMutationInput.object(
          e,
          path,
          apply
              ? Set.of(
                  "requestEntryId", "reviewToken", "expectedItemVersion", "leaseToken", "values")
              : cancel ? Set.of("requestEntryId") : Set.of("requestEntryId", "reviewToken"));
      UUID id = CareMutationInput.uuid(e.path("requestEntryId"), path + ".requestEntryId", true);
      if (!ids.add(id))
        throw CareItemInput.invalid(path + ".requestEntryId", "같은 요청 항목을 두 번 선택할 수 없습니다.");
      String token =
          cancel
              ? null
              : CareMutationInput.text(e.path("reviewToken"), path + ".reviewToken", true);
      if (token != null && !token.matches("[0-9a-f]{64}"))
        throw CareItemInput.invalid(path + ".reviewToken", "상세 조회의 검토 확인값을 전달해주세요.");
      result.add(
          new SelectionEntry(
              id,
              token,
              CareMutationInput.version(
                  e.path("expectedItemVersion"), path + ".expectedItemVersion", false),
              CareMutationInput.text(e.path("leaseToken"), path + ".leaseToken", false),
              e.has("values") ? e.get("values") : null));
    }
    return new Selection(key, List.copyOf(result));
  }

  private Command begin(
      UUID actor, UUID space, UUID key, String kind, UUID request, JsonNode logical) {
    String scope = "SPACE:" + space + (request == null ? "" : ":REQUEST:" + request);
    String digest =
        digests.digest(
            kind, scope, actor.toString(), mapper.writeValueAsString(canonical(logical)));
    if (!receipts.tryLock(actor, key)) throw new ApiException(CommonErrorCode.REQUEST_IN_PROGRESS);
    var previous = receipts.find(actor, key).orElse(null);
    if (previous != null
        && (!previous.commandKind().equals(kind)
            || !previous.scopeKey().equals(scope)
            || !previous.requestDigest().equals(digest)))
      throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    return new Command(actor, key, kind, scope, digest, previous);
  }

  private UUID replay(Command command, UUID member) {
    if (!member.toString().equals(command.previous().resultRefs().get("membershipId")))
      throw forbidden();
    return UUID.fromString(command.previous().resultRefs().get("resultId"));
  }

  private void save(Command c, UUID member, UUID id, Instant now) {
    receipts.saveSuccess(
        c.actor(),
        c.key(),
        new CommandReceipt(
            c.kind(),
            c.scope(),
            c.digest(),
            Map.of("resultId", id.toString(), "membershipId", member.toString())),
        now);
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

  private void validateSize(JsonNode input) {
    CareMutationSizeLimits.strings(input, "body");
    CareMutationSizeLimits.bytes(mapper, input, CareMutationSizeLimits.REQUEST_BYTES, "body");
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private String text(UUID id) {
    return id == null ? null : id.toString();
  }

  private ApiException missing() {
    return new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
  }

  private ApiException forbidden() {
    return new ApiException(CommonErrorCode.FORBIDDEN);
  }

  private ApiException state() {
    return new ApiException(ProposalErrorCode.CHANGE_REQUEST_STATE_CONFLICT);
  }

  private ApiException batchConflict() {
    return new ApiException(ProposalErrorCode.PROPOSAL_BATCH_CONFLICT);
  }

  private record Command(
      UUID actor, UUID key, String kind, String scope, String digest, CommandReceipt previous) {}

  private record Selection(UUID key, List<SelectionEntry> entries) {}

  private record SelectionEntry(
      UUID id, String token, Long version, String lease, JsonNode values) {
    @Override
    public String toString() {
      return "RequestSelection[id=" + id + "]";
    }
  }

  private record DecisionPlan(
      ChangeRequestRepository.Entry entry, CareItemApplicationService.Prepared prepared) {}
}
