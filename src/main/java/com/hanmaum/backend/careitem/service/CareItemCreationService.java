package com.hanmaum.backend.careitem.service;

import com.hanmaum.backend.careitem.dto.*;
import com.hanmaum.backend.careitem.repository.CareItemRepository;
import com.hanmaum.backend.carespace.service.CareSpaceAccessService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.*;
import com.hanmaum.backend.global.exception.ApiException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class CareItemCreationService {
  private static final String KIND = "CREATE_CARE_ITEM";
  private final CareItemRepository items;
  private final CareSpaceAccessService access;
  private final CommandReceiptRepository receipts;
  private final CommandDigest digests;
  private final ObjectMapper mapper;
  private final Clock clock;

  public CareItemCreationService(
      CareItemRepository items,
      CareSpaceAccessService access,
      CommandReceiptRepository receipts,
      CommandDigest digests,
      ObjectMapper mapper,
      Clock clock) {
    this.items = items;
    this.access = access;
    this.receipts = receipts;
    this.digests = digests;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public CreateCareItemResponse create(UUID actor, UUID space, CreateCareItemRequest request) {
    CareItemInput.validate(request);
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("title", request.title());
    values.put("description", request.description());
    values.put("fields", request.fields());
    String body = mapper.writeValueAsString(values);
    UUID key = request.clientRequestId();
    String scope = "SPACE:" + space;
    String digest =
        digests.digest(actor.toString(), key.toString(), KIND, scope, request.itemType(), body);
    if (!receipts.tryLock(actor, key)) throw new ApiException(CommonErrorCode.REQUEST_IN_PROGRESS);
    var previous = receipts.find(actor, key);
    if (previous.isPresent()) {
      var receipt = previous.get();
      if (!KIND.equals(receipt.commandKind())
          || !scope.equals(receipt.scopeKey())
          || !digest.equals(receipt.requestDigest()))
        throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
      var current = access.requireOwner(actor, space);
      if (!current.membershipId().toString().equals(receipt.resultRefs().get("membershipId")))
        throw new ApiException(CommonErrorCode.FORBIDDEN);
      return items
          .replay(space, UUID.fromString(receipt.resultRefs().get("itemId")))
          .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
    var current = access.requireOwner(actor, space);
    UUID item = UUID.randomUUID();
    var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    receipts.saveSuccess(
        actor,
        key,
        new CommandReceipt(
            KIND,
            scope,
            digest,
            Map.of("itemId", item.toString(), "membershipId", current.membershipId().toString())),
        now);
    items.insert(item, space, current.membershipId(), actor, key, body, now);
    return new CreateCareItemResponse(item, "SCHEDULE", "1", now);
  }
}
