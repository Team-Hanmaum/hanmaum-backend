package com.hanmaum.backend.careitem.service;

import com.hanmaum.backend.careitem.dto.UpdateCareItemResponse;
import com.hanmaum.backend.careitem.repository.CareItemApplicationRepository;
import com.hanmaum.backend.carespace.dto.CareSpaceRole;
import com.hanmaum.backend.carespace.service.CareSpaceAccessService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.*;
import com.hanmaum.backend.global.exception.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class DirectCareItemUpdateService {
  private final CareSpaceAccessService access;
  private final CareItemApplicationService applications;
  private final CareItemApplicationRepository items;
  private final EditLockService locks;
  private final CommandReceiptRepository receipts;
  private final CommandDigest digests;
  private final ObjectMapper mapper;
  private final Clock clock;

  public DirectCareItemUpdateService(
      CareSpaceAccessService access,
      CareItemApplicationService applications,
      CareItemApplicationRepository items,
      EditLockService locks,
      CommandReceiptRepository receipts,
      CommandDigest digests,
      ObjectMapper mapper,
      Clock clock) {
    this.access = access;
    this.applications = applications;
    this.items = items;
    this.locks = locks;
    this.receipts = receipts;
    this.digests = digests;
    this.mapper = mapper;
    this.clock = clock;
  }

  public UpdateCareItemResponse update(
      UUID actor, UUID space, UUID id, JsonNode input, boolean statusOnly) {
    String field = statusOnly ? "status" : "values";
    CareMutationInput.object(
        input, "body", Set.of("clientRequestId", "expectedItemVersion", "leaseToken", field));
    CareMutationSizeLimits.strings(input, "body");
    CareMutationSizeLimits.bytes(mapper, input, CareMutationSizeLimits.REQUEST_BYTES, "body");
    UUID key = CareMutationInput.uuid(input.path("clientRequestId"), "clientRequestId", true);
    long expected =
        CareMutationInput.version(input.path("expectedItemVersion"), "expectedItemVersion", true);
    String token = CareMutationInput.text(input.path("leaseToken"), "leaseToken", true);
    JsonNode values;
    if (statusOnly)
      values =
          mapper
              .createObjectNode()
              .put("status", CareMutationInput.text(input.path(field), field, true));
    else {
      values = input.path(field);
      CareMutationInput.object(values, field, Set.of("title", "description", "fields"));
    }
    String kind = statusOnly ? "CHANGE_ITEM_STATUS" : "EDIT_CARE_ITEM";
    String scope = "SPACE:" + space + ":ITEM:" + id;
    String versionInput = Long.toString(expected);
    String canonicalValues = mapper.writeValueAsString(CommandJson.canonical(mapper, values));
    String digest =
        digests.digest(
            actor.toString(), key.toString(), kind, scope, versionInput, canonicalValues);
    if (!receipts.tryLock(actor, key)) throw new ApiException(CommonErrorCode.REQUEST_IN_PROGRESS);
    var member =
        statusOnly ? access.requireActiveMember(actor, space) : access.requireOwner(actor, space);
    var target = applications.lock(space, id);
    if (!target.available()) throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
    if (member.role() != CareSpaceRole.OWNER
        && (!statusOnly
            || !"TASK".equals(target.type())
            || !member
                .membershipId()
                .toString()
                .equalsIgnoreCase(
                    mapper.readTree(target.body()).at("/fields/assigneeMembershipId").asText())))
      throw new ApiException(CommonErrorCode.FORBIDDEN);
    var prior = receipts.find(actor, key);
    if (prior.isPresent()) {
      var stored = prior.get();
      if (!kind.equals(stored.commandKind()) || !scope.equals(stored.scopeKey()))
        throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
      // Pre-fix receipts were keyed by (actor, request ID) but omitted the ID from the HMAC.
      if (!digest.equals(stored.requestDigest())
          && !digests
              .digest(actor.toString(), kind, scope, versionInput, canonicalValues)
              .equals(stored.requestDigest()))
        throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
      var refs = stored.resultRefs();
      if (!member.membershipId().toString().equals(refs.get("membershipId")))
        throw new ApiException(CommonErrorCode.FORBIDDEN);
      return new UpdateCareItemResponse(
          id, refs.get("version"), Instant.parse(refs.get("updatedAt")));
    }
    applications.requireEditable(target, expected);
    locks.requireForWrite(space, id, member.membershipId(), token);
    var prepared = applications.prepareDirect(space, target, values, field);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    UUID source =
        items.directInput(space, mapper.writeValueAsString(values), target.document(), now);
    var result =
        applications.applyDirect(
            space, member.membershipId(), prepared, source, member.membershipId(), now, now);
    locks.releaseAfterSave(space, id);
    String version = Long.toString(result.version());
    receipts.saveSuccess(
        actor,
        key,
        new CommandReceipt(
            kind,
            scope,
            digest,
            Map.of(
                "membershipId",
                member.membershipId().toString(),
                "itemId",
                id.toString(),
                "version",
                version,
                "updatedAt",
                now.toString())),
        now);
    return new UpdateCareItemResponse(id, version, now);
  }
}
