package com.hanmaum.backend.carespace.controller;

import com.hanmaum.backend.carespace.dto.*;
import com.hanmaum.backend.carespace.service.*;
import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(value = "/api/spaces/{spaceId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "CareSpace")
public class CareSpaceDeletionController {
  private final CareSpaceDeletionImpactService impacts;
  private final CareSpaceDeletionService deletions;

  public CareSpaceDeletionController(
      CareSpaceDeletionImpactService impacts, CareSpaceDeletionService deletions) {
    this.impacts = impacts;
    this.deletions = deletions;
  }

  @GetMapping("/deletion-impact")
  @Operation(
      summary = "공간 전체 삭제 영향 확인",
      description =
          "현재 소유자만 삭제·무효 상태를 포함한 전체 건수와 영향 확인값을 조회합니다. 공간 이름·본문은 반환하지 않으며 별도 토큰 TTL은 없습니다.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "전체 집계와 사용자·공간·현재 영향 그래프 확인값"),
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
  public ResponseEntity<ApiResponse<CareSpaceDeletionImpact>> preview(
      @CurrentUser AuthenticatedUser user, @PathVariable UUID spaceId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(impacts.preview(user.userId(), spaceId)));
  }

  @PostMapping(value = "/deletions", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "공간 전체 삭제",
      description =
          "현재 소유자·공간 버전·확인한 전체 영향을 재검증하고 하나의 트랜잭션으로 서비스 데이터를 제거한 뒤 200을 반환합니다. 삭제 후 재요청은 404이며 MVP 복구는 없습니다.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      required = true,
      content = @Content(schema = @Schema(implementation = DeleteBody.class)))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "공간과 소속 서비스 데이터 삭제 완료"),
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
        responseCode = "409",
        ref = "#/components/responses/DELETE_CARE_SPACE_CONFLICT"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "413",
        ref = "#/components/responses/PAYLOAD_TOO_LARGE"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR")
  })
  public ResponseEntity<ApiResponse<DeleteCareSpaceResponse>> delete(
      @CurrentUser AuthenticatedUser user, @PathVariable UUID spaceId, @RequestBody JsonNode body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(deletions.delete(user.userId(), spaceId, body)));
  }

  @Schema(name = "CareSpaceDeletionBody")
  public record DeleteBody(
      @Schema(example = "3", pattern = "^[1-9][0-9]*$") String expectedSpaceVersion,
      @Schema(pattern = "^[0-9a-f]{64}$") String impactToken,
      UUID clientRequestId) {}
}
