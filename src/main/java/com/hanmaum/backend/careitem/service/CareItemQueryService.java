package com.hanmaum.backend.careitem.service;

import com.hanmaum.backend.careitem.dto.*;
import com.hanmaum.backend.careitem.repository.CareItemQueryRepository;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CareItemQueryService {
  private static final Map<String, Set<String>> STATUSES =
      Map.of(
          "SCHEDULE",
          Set.of("SCHEDULED", "COMPLETED", "CANCELED"),
          "TASK",
          Set.of("NOT_STARTED", "IN_PROGRESS", "COMPLETED"),
          "QUESTION",
          Set.of("UNCONFIRMED", "CONFIRMED"),
          "OBSERVATION",
          Set.of(),
          "GUIDANCE",
          Set.of());
  private final CareItemQueryRepository items;

  public CareItemQueryService(CareItemQueryRepository items) {
    this.items = items;
  }

  public CareItemListResponse list(
      UUID actor, UUID space, String type, String status, String scheduleView) {
    if (scheduleView != null) throw CareItemInput.invalid("scheduleView", "예정/지난 일정 필터는 후속 기능입니다.");
    if (type != null && !STATUSES.containsKey(type))
      throw CareItemInput.invalid("itemType", "항목 유형을 확인해주세요.");
    if (status != null && (type == null || !STATUSES.get(type).contains(status)))
      throw CareItemInput.invalid("status", "itemType에 맞는 상태를 입력해주세요.");
    var result = require(items.read(actor, space, null, type, status));
    return new CareItemListResponse(
        result.items().stream()
            .map(
                i ->
                    new CareItemSummary(
                        i.itemId(),
                        i.itemType(),
                        i.version(),
                        i.title(),
                        i.status(),
                        i.needsReconfirmation(),
                        i.needsConflictReview(),
                        i.fields(),
                        null))
            .toList());
  }

  public CareItemDetail detail(UUID actor, UUID space, UUID item) {
    var result = require(items.read(actor, space, item, null, null));
    if (result.items().isEmpty()) throw new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND);
    return result.items().getFirst();
  }

  private CareItemQueryRepository.Result require(Optional<CareItemQueryRepository.Result> result) {
    var value = result.orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    if (!value.authorized()) throw new ApiException(CommonErrorCode.FORBIDDEN);
    return value;
  }
}
