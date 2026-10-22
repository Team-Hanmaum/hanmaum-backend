package com.hanmaum.backend.careitem.controller;

import com.hanmaum.backend.careitem.dto.*;
import com.hanmaum.backend.careitem.service.*;
import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/spaces/{spaceId}/items", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "CareItem", description = "일정 직접 등록·다섯 유형 조회·항목별 편집 잠금")
@SecurityRequirement(name = "SessionCookie")
@ApiResponses({
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      ref = "#/components/responses/INVALID_REQUEST"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "401",
      ref = "#/components/responses/UNAUTHENTICATED"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "403",
      ref = "#/components/responses/FORBIDDEN"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      ref = "#/components/responses/RESOURCE_NOT_FOUND"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "500",
      ref = "#/components/responses/INTERNAL_ERROR")
})
public class CareItemController {
  private final CareItemCreationService creation;
  private final CareItemQueryService queries;

  public CareItemController(CareItemCreationService creation, CareItemQueryService queries) {
    this.creation = creation;
    this.queries = queries;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "일정 직접 등록",
      description = "현재 소유자만 SCHEDULE 등록. 제목·직접 입력 근거·최초 이력·성공 기록을 함께 저장. 날짜/시각/장소 null 허용.",
      security = @SecurityRequirement(name = "CsrfToken"))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "201",
      description = "등록 성공 또는 동일 성공 요청의 재전송")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "409",
      ref = "#/components/responses/CREATE_CARE_ITEM_CONFLICT")
  public ResponseEntity<ApiResponse<CreateCareItemResponse>> create(
      @CurrentUser AuthenticatedUser actor,
      @PathVariable UUID spaceId,
      @RequestBody CreateCareItemRequest request) {
    var result = creation.create(actor.userId(), spaceId, request);
    return ResponseEntity.created(
            URI.create("/api/spaces/" + spaceId + "/items/" + result.itemId()))
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(result));
  }

  @GetMapping
  @Operation(
      summary = "관리 항목 목록 조회",
      description =
          "전체 조회, 생성 시각·ID 내림차순. itemType/status 필터, 일반 삭제 제외. scheduleView는 미지원 오류. 현재 등록 가능 유형은 SCHEDULE.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "목록 조회 성공, 빈 목록은 items=[]")
  public ResponseEntity<ApiResponse<CareItemListResponse>> list(
      @CurrentUser AuthenticatedUser actor,
      @PathVariable UUID spaceId,
      @RequestParam(required = false) String itemType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String scheduleView) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.success(
                queries.list(actor.userId(), spaceId, itemType, status, scheduleView)));
  }

  @GetMapping("/{itemId}")
  @Operation(
      summary = "관리 항목 상세 조회",
      description =
          "현재 참여자만 조회. 삭제된 내용을 재노출하지 않음. editLock은 현재 잠금 요약, confirmedAnswer는 소유자 확정 답변. answers=[]는 별도 답변 제안 기능의 예약 필드.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "항목 상세 조회 성공")
  public ResponseEntity<ApiResponse<CareItemDetail>> detail(
      @CurrentUser AuthenticatedUser actor, @PathVariable UUID spaceId, @PathVariable UUID itemId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(queries.detail(actor.userId(), spaceId, itemId)));
  }
}
