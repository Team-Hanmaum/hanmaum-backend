package com.hanmaum.backend.careitem.service;

import com.hanmaum.backend.careitem.code.CareItemErrorCode;
import com.hanmaum.backend.careitem.dto.CareItemReconfirmationResponse;
import com.hanmaum.backend.careitem.dto.EditLockPurpose;
import com.hanmaum.backend.careitem.repository.CareItemApplicationRepository;
import com.hanmaum.backend.careitem.repository.CareItemProvenanceRepository;
import com.hanmaum.backend.carespace.service.CareSpaceAccessService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.CommandDigest;
import com.hanmaum.backend.global.command.CommandJson;
import com.hanmaum.backend.global.command.CommandReceipt;
import com.hanmaum.backend.global.command.CommandReceiptRepository;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.proposal.service.ProposalProvenanceService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class CareItemReconfirmationService {
  private static final String KIND = "RECONFIRM_CARE_ITEM";
  private final CareSpaceAccessService access;
  private final CareItemApplicationService applications;
  private final CareItemApplicationRepository items;
  private final CareItemProvenanceRepository evidence;
  private final ProposalProvenanceService proposals;
  private final EditLockService locks;
  private final CommandReceiptRepository receipts;
  private final CommandDigest digests;
  private final ObjectMapper mapper;
  private final Clock clock;

  public CareItemReconfirmationService(
      CareSpaceAccessService access,
      CareItemApplicationService applications,
      CareItemApplicationRepository items,
      CareItemProvenanceRepository evidence,
      ProposalProvenanceService proposals,
      EditLockService locks,
      CommandReceiptRepository receipts,
      CommandDigest digests,
      ObjectMapper mapper,
      Clock clock) {
    this.access = access;
    this.applications = applications;
    this.items = items;
    this.evidence = evidence;
    this.proposals = proposals;
    this.locks = locks;
    this.receipts = receipts;
    this.digests = digests;
    this.mapper = mapper;
    this.clock = clock;
  }

  public CareItemReconfirmationResponse reconfirm(UUID actor, UUID space, UUID id, JsonNode input) {
    CareMutationInput.object(
        input,
        "body",
        Set.of(
            "expectedItemVersion",
            "leaseToken",
            "values",
            "basis",
            "evidenceIds",
            "clientRequestId"));
    CareMutationSizeLimits.strings(input, "body");
    CareMutationSizeLimits.bytes(mapper, input, CareMutationSizeLimits.REQUEST_BYTES, "body");
    UUID key = CareMutationInput.uuid(input.path("clientRequestId"), "clientRequestId", true);
    long expected =
        CareMutationInput.version(input.path("expectedItemVersion"), "expectedItemVersion", true);
    String token = CareMutationInput.text(input.path("leaseToken"), "leaseToken", true);
    String basis = CareMutationInput.text(input.path("basis"), "basis", false);
    if (basis != null
        && basis.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)))
      throw CareItemInput.invalid("basis", "공백이 아닌 근거를 입력해주세요.");
    Set<UUID> selected = evidenceIds(input.path("evidenceIds"));
    if (basis == null && selected.isEmpty())
      throw CareItemInput.invalid("basis", "새 근거를 입력하거나 유효한 기존 근거를 선택해주세요.");
    JsonNode values = input.path("values");
    CareMutationInput.object(values, "values", Set.of("title", "description", "fields"));

    String scope = "SPACE:" + space + ":ITEM:" + id;
    String digest =
        digests.digest(
            actor.toString(),
            key.toString(),
            KIND,
            scope,
            Long.toString(expected),
            mapper.writeValueAsString(CommandJson.canonical(mapper, values)),
            mapper.writeValueAsString(basis),
            mapper.writeValueAsString(selected.stream().sorted().toList()));
    if (!receipts.tryLock(actor, key)) throw new ApiException(CommonErrorCode.REQUEST_IN_PROGRESS);
    // This holds the same space row lock as source deletion through the commit.
    UUID owner = access.requireOwner(actor, space).membershipId();
    var item = applications.lock(space, id);
    if (!item.visible()) throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);

    var previous = receipts.find(actor, key);
    if (previous.isPresent()) {
      var receipt = previous.get();
      if (!KIND.equals(receipt.commandKind())
          || !scope.equals(receipt.scopeKey())
          || !digest.equals(receipt.requestDigest()))
        throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
      var refs = receipt.resultRefs();
      if (!owner.toString().equals(refs.get("membershipId")))
        throw new ApiException(CommonErrorCode.FORBIDDEN);
      return new CareItemReconfirmationResponse(
          id, refs.get("version"), false, Instant.parse(refs.get("updatedAt")));
    }

    if (item.version() != expected) throw new ApiException(CommonErrorCode.VERSION_CONFLICT);
    if (item.available() || expected == Long.MAX_VALUE)
      throw new ApiException(CareItemErrorCode.ITEM_STATE_CONFLICT);
    locks.requireForWrite(space, id, owner, token, EditLockPurpose.RECONFIRMATION);
    var normalized = applications.reconfirmedValues(space, item, values);
    var retained =
        evidence.validReconfirmationEvidence(
            space, id, selected, proposals.unavailableEvidence(space, selected));
    if (retained.size() != selected.size())
      throw new ApiException(CareItemErrorCode.ITEM_STATE_CONFLICT);
    // A valid quote or a partial edit alone cannot establish every submitted field.
    if (basis == null
        && retained.stream()
            .noneMatch(row -> supportsAllValues(row.independentValues(), normalized)))
      throw CareItemInput.invalid("basis", "모든 값을 확인할 수 있는 독립 근거가 없으면 새 근거를 입력해주세요.");

    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    UUID inputDocument = null;
    if (basis != null) {
      var source = mapper.createObjectNode().put("basis", basis);
      source.set("values", normalized);
      CareMutationSizeLimits.bytes(mapper, source, CareMutationSizeLimits.DOCUMENT_BYTES, "basis");
      inputDocument = items.reconfirmationInput(space, mapper.writeValueAsString(source), now);
      items.directBasis(space, id, inputDocument, owner, now);
    }
    // Start a new document lineage. The old current document may have been erased.
    UUID document =
        items.document(
            space, mapper.writeValueAsString(normalized), null, inputDocument, null, now);
    for (var retainedEvidence : retained) {
      items.linkReconfirmationDocument(
          space, document, retainedEvidence.directDocument(), retainedEvidence.sourceEvidence());
      if (inputDocument != null)
        items.linkReconfirmationDocument(
            space,
            inputDocument,
            retainedEvidence.directDocument(),
            retainedEvidence.sourceEvidence());
    }
    items.scopeAnalysisBasisToReconfirmation(space, id, document);
    items.reconfirm(space, id, expected, document, now);
    items.history(space, id, expected + 1, "RECONFIRM", document, item.status(), owner, now);
    locks.releaseAfterSave(space, id);

    String version = Long.toString(expected + 1);
    receipts.saveSuccess(
        actor,
        key,
        new CommandReceipt(
            KIND,
            scope,
            digest,
            Map.of(
                "membershipId",
                owner.toString(),
                "itemId",
                id.toString(),
                "version",
                version,
                "updatedAt",
                now.toString())),
        now);
    return new CareItemReconfirmationResponse(id, version, false, now);
  }

  private Set<UUID> evidenceIds(JsonNode input) {
    if (!input.isArray()) throw CareItemInput.invalid("evidenceIds", "근거 ID 배열을 입력해주세요.");
    Set<UUID> result = new HashSet<>();
    for (int i = 0; i < input.size(); i++) {
      String path = "evidenceIds[" + i + "]";
      if (!result.add(CareMutationInput.uuid(input.get(i), path, true)))
        throw CareItemInput.invalid(path, "같은 근거를 한 번만 선택해주세요.");
    }
    return result;
  }

  private boolean supportsAllValues(String source, JsonNode values) {
    if (source == null) return false;
    JsonNode proof = mapper.readTree(source);
    if (proof.has("values")) proof = proof.path("values");
    if (!proof.path("fields").isObject()) return false;
    if (!sameValue(proof.path("title"), values.path("title"))
        || !sameValue(proof.path("description"), values.path("description"))
        || !sameValue(proof.path("confirmedAnswer"), values.path("confirmedAnswer"))) return false;
    JsonNode proofFields = proof.path("fields");
    JsonNode fields = values.path("fields");
    return fields.propertyNames().stream()
        .allMatch(key -> sameValue(proofFields.path(key), fields.path(key)));
  }

  private boolean sameValue(JsonNode left, JsonNode right) {
    return left.equals(right)
        || ((left.isMissingNode() || left.isNull()) && (right.isMissingNode() || right.isNull()));
  }
}
