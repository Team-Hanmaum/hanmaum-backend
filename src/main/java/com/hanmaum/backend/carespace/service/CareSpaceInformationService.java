package com.hanmaum.backend.carespace.service;

import com.hanmaum.backend.careitem.service.CareMutationInput;
import com.hanmaum.backend.carespace.dto.UpdateCareSpaceResponse;
import com.hanmaum.backend.carespace.repository.CareSpaceInformationRepository;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.*;
import com.hanmaum.backend.global.exception.ApiException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class CareSpaceInformationService {
  private final CareSpaceAccessService access;
  private final CareSpaceInformationApplicationService applications;
  private final CareSpaceInformationRepository spaces;
  private final CommandReceiptRepository receipts;
  private final CommandDigest digests;
  private final ObjectMapper mapper;
  private final Clock clock;

  public CareSpaceInformationService(
      CareSpaceAccessService access,
      CareSpaceInformationApplicationService applications,
      CareSpaceInformationRepository spaces,
      CommandReceiptRepository receipts,
      CommandDigest digests,
      ObjectMapper mapper,
      Clock clock) {
    this.access = access;
    this.applications = applications;
    this.spaces = spaces;
    this.receipts = receipts;
    this.digests = digests;
    this.mapper = mapper;
    this.clock = clock;
  }

  public UpdateCareSpaceResponse update(UUID actor, UUID space, JsonNode input) {
    CareMutationInput.object(
        input, "body", Set.of("subjectLabel", "expectedSpaceVersion", "clientRequestId"));
    String label = applications.label(input.path("subjectLabel"));
    long expected =
        CareMutationInput.version(input.path("expectedSpaceVersion"), "expectedSpaceVersion", true);
    UUID key = CareMutationInput.uuid(input.path("clientRequestId"), "clientRequestId", true);
    String kind = "UPDATE_CARE_SPACE", scope = "SPACE:" + space;
    String digest =
        digests.digest(
            actor.toString(), key.toString(), kind, scope, Long.toString(expected), label);
    if (!receipts.tryLock(actor, key)) throw new ApiException(CommonErrorCode.REQUEST_IN_PROGRESS);
    var previous = receipts.find(actor, key);
    if (previous.isPresent()
        && (!kind.equals(previous.get().commandKind())
            || !scope.equals(previous.get().scopeKey())
            || !digest.equals(previous.get().requestDigest())))
      throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    var current = access.requireOwner(actor, space);
    if (previous.isPresent()) {
      var refs = previous.get().resultRefs();
      if (!current.membershipId().toString().equals(refs.get("membershipId")))
        throw new ApiException(CommonErrorCode.FORBIDDEN);
      String savedLabel =
          spaces
              .label(space, UUID.fromString(refs.get("documentId")))
              .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
      return new UpdateCareSpaceResponse(
          space, savedLabel, refs.get("version"), Instant.parse(refs.get("updatedAt")));
    }
    var values = mapper.createObjectNode().put("subjectLabel", label);
    var plan = applications.prepare(space, expected, values);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    var result = applications.apply(space, plan, now);
    receipts.saveSuccess(
        actor,
        key,
        new CommandReceipt(
            kind,
            scope,
            digest,
            Map.of(
                "membershipId",
                current.membershipId().toString(),
                "documentId",
                result.document().toString(),
                "version",
                Long.toString(result.version()),
                "updatedAt",
                result.updatedAt().toString())),
        now);
    return new UpdateCareSpaceResponse(
        space, result.label(), Long.toString(result.version()), result.updatedAt());
  }
}
