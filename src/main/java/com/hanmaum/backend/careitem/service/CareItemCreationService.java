package com.hanmaum.backend.careitem.service;

import com.hanmaum.backend.careitem.dto.*;
import com.hanmaum.backend.careitem.repository.CareItemRepository;
import com.hanmaum.backend.carespace.dto.CareSpaceRole;
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
  private final CareItemApplicationService applications;

  public CareItemCreationService(
      CareItemRepository items,
      CareSpaceAccessService access,
      CommandReceiptRepository receipts,
      CommandDigest digests,
      ObjectMapper mapper,
      Clock clock,
      CareItemApplicationService applications) {
    this.applications = applications;
    this.items = items;
    this.access = access;
    this.receipts = receipts;
    this.digests = digests;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public CreateCareItemResponse create(UUID actor, UUID space, CreateCareItemRequest request) {
    if (request == null || request.clientRequestId() == null)
      throw CareItemInput.invalid("clientRequestId", "요청 ID를 입력해주세요.");
    var input =
        mapper
            .createObjectNode()
            .put("itemType", request.itemType())
            .put("title", request.title())
            .put("description", request.description());
    input.set("fields", mapper.valueToTree(request.fields()));
    CareMutationInput.text(input.path("itemType"), "itemType", true);
    CareMutationSizeLimits.strings(input, "body");
    CareMutationSizeLimits.bytes(mapper, input, CareMutationSizeLimits.REQUEST_BYTES, "body");
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
    var current = access.requireActiveMember(actor, space);
    boolean selfTask =
        "TASK".equals(request.itemType())
            && request.fields() instanceof CreateCareItemRequest.TaskFields t
            && current.membershipId().toString().equalsIgnoreCase(t.assigneeMembershipId());
    if (current.role() != CareSpaceRole.OWNER && !selfTask)
      throw new ApiException(CommonErrorCode.FORBIDDEN);
    var previous = receipts.find(actor, key);
    if (previous.isPresent()) {
      var receipt = previous.get();
      if (!KIND.equals(receipt.commandKind())
          || !scope.equals(receipt.scopeKey())
          || !digest.equals(receipt.requestDigest()))
        throw new ApiException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
      if (!current.membershipId().toString().equals(receipt.resultRefs().get("membershipId")))
        throw new ApiException(CommonErrorCode.FORBIDDEN);
      return items
          .replay(space, UUID.fromString(receipt.resultRefs().get("itemId")))
          .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
    var prepared = applications.prepareDirect(space, null, input, "body");
    UUID item = prepared.item();
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
    items.insert(
        item,
        space,
        current.membershipId(),
        actor,
        key,
        mapper.writeValueAsString(prepared.body()),
        prepared.type(),
        prepared.status(),
        now);
    return new CreateCareItemResponse(item, prepared.type(), "1", now);
  }
}
