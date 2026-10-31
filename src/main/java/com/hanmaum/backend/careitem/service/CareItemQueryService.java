package com.hanmaum.backend.careitem.service;

import com.hanmaum.backend.careitem.dto.*;
import com.hanmaum.backend.careitem.repository.CareItemBrowseRepository;
import com.hanmaum.backend.careitem.repository.CareItemQueryRepository;
import com.hanmaum.backend.carespace.service.CareSpaceQueryService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
  private final CareItemBrowseRepository browse;
  private final CareSpaceQueryService spaces;
  private final Clock clock;

  public CareItemQueryService(
      CareItemQueryRepository items,
      CareItemBrowseRepository browse,
      CareSpaceQueryService spaces,
      Clock clock) {
    this.items = items;
    this.browse = browse;
    this.spaces = spaces;
    this.clock = clock;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public CareItemListResponse list(
      UUID actor, UUID space, String type, String status, String viewName) {
    if (type != null && !STATUSES.containsKey(type))
      throw CareItemInput.invalid("itemType", "항목 유형을 확인해주세요.");
    if (status != null && (type == null || !STATUSES.get(type).contains(status)))
      throw CareItemInput.invalid("status", "itemType에 맞는 상태를 입력해주세요.");
    CareItemView view = null;
    if (viewName != null) {
      if (status != null) throw CareItemInput.invalid("view", "status와 view는 함께 사용할 수 없습니다.");
      try {
        view = CareItemView.valueOf(viewName);
      } catch (IllegalArgumentException e) {
        throw CareItemInput.invalid("view", "지원하는 목록 구분을 입력해주세요.");
      }
      if (!view.itemType().equals(type))
        throw CareItemInput.invalid("view", "itemType에 맞는 목록 구분을 입력해주세요.");
    }
    spaces.detail(actor, space);
    Instant asOf = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var section = browse.section(space, type, status, view, asOf, null);
    return new CareItemListResponse(
        section.items(),
        section.totalCount(),
        browse.reconfirmations(space, type, status, asOf),
        view == CareItemView.UPCOMING ? browse.pastCount(space, asOf) : null);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public CareItemHighlights highlights(UUID actor, UUID space, Instant asOf) {
    spaces.detail(actor, space);
    return new CareItemHighlights(
        preview(space, CareItemView.UPCOMING, asOf, 2),
        browse.pastCount(space, asOf),
        preview(space, CareItemView.REMAINING, asOf, 3),
        preview(space, CareItemView.UNCONFIRMED, asOf, 2));
  }

  private CareItemSection preview(UUID space, CareItemView view, Instant asOf, int limit) {
    return browse.section(space, view.itemType(), null, view, asOf, limit);
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
