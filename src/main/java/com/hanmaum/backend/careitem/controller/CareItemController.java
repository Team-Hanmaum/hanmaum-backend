package com.hanmaum.backend.careitem.controller;

import com.hanmaum.backend.careitem.dto.*;
import com.hanmaum.backend.careitem.service.*;
import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(value = "/api/spaces/{spaceId}/items", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "CareItem", description = "관리 항목 등록·조회·수정·상태 변경·항목별 편집 잠금")
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

  private final DirectCareItemUpdateService updates;

  public CareItemController(
      CareItemCreationService creation,
      CareItemQueryService queries,
      DirectCareItemUpdateService updates) {
    this.updates = updates;
    this.creation = creation;
    this.queries = queries;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "일정·할 일·질문 직접 등록",
      description = "소유자는 세 유형을 등록하고 구성원은 본인 담당 TASK만 즉시 등록합니다. 그 외 구성원 입력은 변경 요청으로 제출합니다.",
      security = @SecurityRequirement(name = "CsrfToken"))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "201",
      description = "등록 성공 또는 동일 성공 요청의 재전송")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "409",
      ref = "#/components/responses/CREATE_CARE_ITEM_CONFLICT")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "413",
      ref = "#/components/responses/PAYLOAD_TOO_LARGE")
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
          "유형별 전체 조회. status 또는 view 중 하나를 사용합니다. 예정·지난 일정은 한국 시간 기준이며 재확인 항목은 별도 배열로 반환합니다.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "목록 조회 성공, 빈 목록은 items=[]")
  public ResponseEntity<ApiResponse<CareItemListResponse>> list(
      @CurrentUser AuthenticatedUser actor,
      @PathVariable UUID spaceId,
      @RequestParam(required = false) String itemType,
      @RequestParam(required = false) String status,
      @Parameter(
              description =
                  "SCHEDULE: UPCOMING/PAST/CLOSED, TASK: REMAINING/COMPLETED, QUESTION: UNCONFIRMED/CONFIRMED",
              schema = @Schema(implementation = CareItemView.class))
          @RequestParam(required = false)
          String view,
      @Parameter(hidden = true) @RequestParam(required = false) String scheduleView) {
    if (scheduleView != null) throw CareItemInput.invalid("scheduleView", "view로 목록 구분을 전달해주세요.");
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(queries.list(actor.userId(), spaceId, itemType, status, view)));
  }

  public record ContentPatch(
      UUID clientRequestId, String expectedItemVersion, String leaseToken, JsonNode values) {}

  public record StatusPatch(
      UUID clientRequestId, String expectedItemVersion, String leaseToken, String status) {}

  @PatchMapping(value = "/{itemId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "관리 항목 내용 수정",
      description = "소유자만 허용. values는 title, description, fields의 변경분이며 생략한 값은 유지합니다.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = @Content(schema = @Schema(implementation = ContentPatch.class)))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "내용 수정 완료")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "409",
      description = "버전·편집 잠금·요청 충돌")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "413",
      ref = "#/components/responses/PAYLOAD_TOO_LARGE")
  public ResponseEntity<ApiResponse<UpdateCareItemResponse>> update(
      @CurrentUser AuthenticatedUser actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID itemId,
      @RequestBody JsonNode body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(updates.update(actor.userId(), spaceId, itemId, body, false)));
  }

  @PatchMapping(value = "/{itemId}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "일정·할 일 상태 변경",
      description = "소유자 또는 해당 할 일의 현재 담당자만 허용. 제목·담당자 등 다른 필드는 받지 않습니다.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = @Content(schema = @Schema(implementation = StatusPatch.class)))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "상태 변경 완료")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "409",
      description =
          "ITEM_STATE_CONFLICT / VERSION_CONFLICT / ITEM_LOCKED / EDIT_LOCK_EXPIRED / IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "413",
      ref = "#/components/responses/PAYLOAD_TOO_LARGE")
  public ResponseEntity<ApiResponse<UpdateCareItemResponse>> status(
      @CurrentUser AuthenticatedUser actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID itemId,
      @RequestBody JsonNode body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(updates.update(actor.userId(), spaceId, itemId, body, true)));
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
