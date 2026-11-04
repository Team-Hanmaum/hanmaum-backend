package com.hanmaum.backend.careitem.controller;

import com.hanmaum.backend.careitem.dto.*;
import com.hanmaum.backend.careitem.service.CareItemProvenanceService;
import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(
    value = "/api/spaces/{spaceId}/items/{itemId}",
    produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "CareItem")
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
public class CareItemProvenanceController {
  private final CareItemProvenanceService queries;

  public CareItemProvenanceController(CareItemProvenanceService queries) {
    this.queries = queries;
  }

  @GetMapping("/evidence")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "근거 목록 조회 성공")
  @Operation(
      summary = "항목 근거 조회",
      description =
          "현재 참여자만 조회. 소식 인용·직접 입력·파생 입력을 구분하며 제거된 내용은 반환하지 않습니다. fieldPaths는 저장하지 않아 빈 배열입니다.")
  public ResponseEntity<ApiResponse<CareItemEvidenceResponse>> evidence(
      @CurrentUser AuthenticatedUser actor, @PathVariable UUID spaceId, @PathVariable UUID itemId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(queries.evidence(actor.userId(), spaceId, itemId)));
  }

  @GetMapping("/history")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "확정 이력 목록 조회 성공")
  @Operation(
      summary = "항목 변경 이력 목록 조회",
      description = "확정된 버전을 내림차순 전체 조회. 대기 제안·요청 제외. 내용이 제거되어도 변경자·시각·버전은 유지합니다.")
  public ResponseEntity<ApiResponse<CareItemHistoryResponse>> history(
      @CurrentUser AuthenticatedUser actor, @PathVariable UUID spaceId, @PathVariable UUID itemId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(queries.history(actor.userId(), spaceId, itemId)));
  }

  @GetMapping("/history/{version}")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "확정 버전 비교 조회 성공")
  @Operation(
      summary = "항목 변경 전후 상세 조회",
      description =
          "선택한 확정 버전과 직전 저장 버전을 비교. 제거된 before/after는 null이고 redactedFields에 표시합니다. 과거 담당자는 해당 시점의 ID를 유지합니다.")
  public ResponseEntity<ApiResponse<CareItemHistoryDetail>> detail(
      @CurrentUser AuthenticatedUser actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID itemId,
      @Parameter(schema = @Schema(type = "string", pattern = "^[1-9][0-9]*$")) @PathVariable
          String version) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(queries.detail(actor.userId(), spaceId, itemId, version)));
  }
}
