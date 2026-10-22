package com.hanmaum.backend.proposal.controller;

import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.*;
import com.hanmaum.backend.proposal.dto.*;
import com.hanmaum.backend.proposal.service.ProposalDecisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(value = "/api/spaces/{spaceId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Proposal")
@SecurityRequirement(name = "SessionCookie")
@SecurityRequirement(name = "CsrfToken")
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
      responseCode = "413",
      ref = "#/components/responses/PAYLOAD_TOO_LARGE"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "409",
      description =
          "PROPOSAL_STATE_CONFLICT / PROPOSAL_BATCH_CONFLICT / VERSION_CONFLICT / ITEM_LOCKED / EDIT_LOCK_EXPIRED / IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "500",
      ref = "#/components/responses/INTERNAL_ERROR")
})
public class ProposalDecisionController {
  private final ProposalDecisionService decisions;

  public ProposalDecisionController(ProposalDecisionService decisions) {
    this.decisions = decisions;
  }

  @PostMapping(value = "/proposal-applications", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "선택한 AI 제안 일괄 반영",
      description =
          "현재 소유자만 1~20개를 전체 성공/실패로 반영. 같은 기존 항목은 한 번만 선택. 상세의 reviewToken과 기존 항목의 최신 버전·본인 leaseToken 필요. values는 최종 검토한 변경분이며 생략 유지, nullable의 null은 비우기. 동작·유형 변경 불가. 원본·근거는 서버에서 결정. 재전송은 같은 clientRequestId와 같은 내용 사용.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content =
          @Content(
              schema = @Schema(implementation = ApplicationBody.class),
              examples =
                  @ExampleObject(
                      value =
                          """
 {"clientRequestId":"832cbe35-9970-4f99-a219-8f019315b3ab","entries":[{"proposalId":"2f4fd7af-5b2f-4d38-b1ed-8ea1141db910","action":"CREATE_ITEM","reviewToken":"<상세 조회의 reviewToken>","targetItemId":null,"expectedItemVersion":null,"leaseToken":null,"values":{"itemType":"SCHEDULE","title":"진료 일정","description":null,"fields":{"scheduledDate":"2026-10-25","scheduledTime":null,"location":null}}}]}
 """)))
  public ResponseEntity<ApiResponse<ProposalApplicationResponse>> apply(
      @CurrentUser AuthenticatedUser user, @PathVariable UUID spaceId, @RequestBody JsonNode body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(decisions.apply(user.userId(), spaceId, body)));
  }

  @PostMapping(value = "/proposal-declines", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "선택한 AI 제안 미적용",
      description =
          "현재 소유자만 1~20개를 함께 미적용. 항목 내용·버전·잠금은 변경하지 않음. 이미 처리되거나 무효한 제안이 있으면 전체 거절. 되돌리기·사유 입력은 미지원.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = @Content(schema = @Schema(implementation = DeclineBody.class)))
  public ResponseEntity<ApiResponse<ProposalDeclineResponse>> decline(
      @CurrentUser AuthenticatedUser user, @PathVariable UUID spaceId, @RequestBody JsonNode body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(decisions.decline(user.userId(), spaceId, body)));
  }

  public record ApplicationBody(
      UUID clientRequestId,
      @ArraySchema(minItems = 1, maxItems = 20) List<ApplicationEntry> entries) {}

  public record ApplicationEntry(
      UUID proposalId,
      ProposalAction action,
      String reviewToken,
      UUID targetItemId,
      @Schema(
              types = {"string", "null"},
              example = "1")
          String expectedItemVersion,
      String leaseToken,
      @Schema(
              description =
                  "CREATE: itemType/title/description/fields. UPDATE: title/description/fields/status 변경분. RESOLVE: confirmedAnswer만. LINK: null")
          JsonNode values) {}

  public record DeclineBody(
      UUID clientRequestId, @ArraySchema(minItems = 1, maxItems = 20) List<DeclineEntry> entries) {}

  public record DeclineEntry(UUID proposalId, String reviewToken) {}
}
