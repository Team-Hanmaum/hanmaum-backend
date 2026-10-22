package com.hanmaum.backend.proposal.controller;

import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.AuthenticatedUser;
import com.hanmaum.backend.global.security.CurrentUser;
import com.hanmaum.backend.proposal.dto.ProposalAction;
import com.hanmaum.backend.proposal.service.ChangeRequestQueryService;
import com.hanmaum.backend.proposal.service.ChangeRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping(value = "/api/spaces/{spaceId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Proposal")
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
public class ChangeRequestController {
  private final ChangeRequestService requests;
  private final ChangeRequestQueryService queries;
  private final ObjectMapper mapper;

  public ChangeRequestController(
      ChangeRequestService requests, ChangeRequestQueryService queries, ObjectMapper mapper) {
    this.requests = requests;
    this.queries = queries;
    this.mapper = mapper;
  }

  @PostMapping(value = "/change-requests", consumes = MediaType.APPLICATION_JSON_VALUE)
  @SecurityRequirement(name = "SessionCookie")
  @SecurityRequirement(name = "CsrfToken")
  @Operation(
      summary = "구성원 AI 제안 변경 요청 제출",
      description =
          "현재 MEMBER가 AI 제안 1~20개를 검증 후 승인 대기로 제출합니다. proposalId 필수이며 현재 항목은 변경하지 않습니다. "
              + "기존 항목은 최신 버전·본인 편집 잠금이 필요하고 성공 시 잠금을 해제합니다. 같은 참여자·제안의 유효 대기 요청은 중복 제출할 수 없습니다. "
              + "동일 요청 재전송에는 기존 clientRequestId를 사용합니다. 본문 1 MiB, 문자열 10,000 UTF-16, 저장 요청 문서 합계 512 KiB 상한입니다.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content =
          @Content(
              schema = @Schema(implementation = SubmissionBody.class),
              examples =
                  @ExampleObject(
                      value =
                          """
              {"clientRequestId":"832cbe35-9970-4f99-a219-8f019315b3ab","entries":[{"proposalId":"2f4fd7af-5b2f-4d38-b1ed-8ea1141db910","action":"CREATE_ITEM","reviewToken":"<구성원 제안 상세의 reviewToken>","values":{"itemType":"SCHEDULE","title":"진료 일정","description":null,"fields":{"scheduledDate":"2026-10-25","scheduledTime":null,"location":null}}}]}
              """)))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "승인 대기 요청 생성. 현재 항목 반영 완료가 아님"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description =
            "CHANGE_REQUEST_ALREADY_PENDING / PROPOSAL_STATE_CONFLICT / PROPOSAL_BATCH_CONFLICT / VERSION_CONFLICT / ITEM_LOCKED / EDIT_LOCK_EXPIRED / IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "413",
        ref = "#/components/responses/PAYLOAD_TOO_LARGE")
  })
  public ResponseEntity<ApiResponse<SubmittedRequest>> submit(
      @CurrentUser AuthenticatedUser user, @PathVariable UUID spaceId, @RequestBody JsonNode body) {
    return response(
        HttpStatus.CREATED, requests.submit(user.userId(), spaceId, body), SubmittedRequest.class);
  }

  @GetMapping("/change-requests")
  @Operation(
      summary = "변경 요청 목록 조회",
      description =
          "현재 참여자 모두 조회합니다. 해당 상태의 항목을 포함하는 요청 묶음을 제출 시각·ID 내림차순으로 반환합니다. 기본 PENDING·20개, 최대 50개. 커서는 동일 공간·필터에서 사용합니다. 조회는 상태를 변경하지 않습니다.")
  public ResponseEntity<ApiResponse<RequestPage>> list(
      @CurrentUser AuthenticatedUser user,
      @PathVariable UUID spaceId,
      @Parameter(
              schema =
                  @Schema(
                      allowableValues = {
                        "PENDING",
                        "APPLIED",
                        "DECLINED",
                        "CANCELED",
                        "INVALIDATED"
                      },
                      defaultValue = "PENDING"))
          @RequestParam(required = false)
          String status,
      @Parameter(description = "이전 응답의 nextCursor. 첫 조회에서는 생략") @RequestParam(required = false)
          String cursor,
      @Parameter(schema = @Schema(minimum = "1", maximum = "50", defaultValue = "20"))
          @RequestParam(defaultValue = "20")
          int size) {
    return response(
        HttpStatus.OK,
        queries.list(user.userId(), spaceId, status, cursor, size),
        RequestPage.class);
  }

  @GetMapping("/change-requests/{requestId}")
  @Operation(
      summary = "변경 요청 상세 및 처리 결과 조회",
      description =
          "현재 참여자 모두 제출 내용·현재 값·근거·결정 이력을 조회합니다. 현재 소유자에게만 유효 PENDING 항목의 reviewToken을 발급합니다. 무효 요청을 새 토큰으로 복구하거나 버전을 자동 갱신하지 않습니다. 삭제된 내용은 null로 가리며 처리 메타데이터는 보존합니다.")
  public ResponseEntity<ApiResponse<RequestDetail>> detail(
      @CurrentUser AuthenticatedUser user,
      @PathVariable UUID spaceId,
      @PathVariable UUID requestId) {
    return response(
        HttpStatus.OK, queries.detail(user.userId(), spaceId, requestId), RequestDetail.class);
  }

  @PostMapping(value = "/request-applications", consumes = MediaType.APPLICATION_JSON_VALUE)
  @SecurityRequirement(name = "SessionCookie")
  @SecurityRequirement(name = "CsrfToken")
  @Operation(
      summary = "소유자 선택 변경 요청 반영",
      description =
          "현재 소유자가 선택한 1~20개 요청을 변경 전 상태에서 검증하고 전부 반영하거나 전부 실패합니다. 기존 항목은 소유자 본인의 잠금과 요청 당시와 같은 현재 버전이 필요합니다. values 생략은 저장된 요청값 사용, 전달하면 해당 작업 입력 전체를 대체하며 원래 requestedValues는 보존합니다. 요청 종류·대상은 바꿀 수 없습니다. 같은 AI 제안의 직접 반영과 중복되지 않습니다.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content =
          @Content(
              schema = @Schema(implementation = ApplicationBody.class),
              examples =
                  @ExampleObject(
                      value =
                          """
          {"clientRequestId":"f692065f-05e1-4b42-bf19-672ded107e46","entries":[{"requestEntryId":"7045c73c-e589-40c6-b4b4-e7baa073f01a","reviewToken":"<소유자 요청 상세의 reviewToken>","expectedItemVersion":null,"leaseToken":null}]}
          """)))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description =
            "CHANGE_REQUEST_STATE_CONFLICT / PROPOSAL_STATE_CONFLICT / PROPOSAL_BATCH_CONFLICT / VERSION_CONFLICT / ITEM_LOCKED / EDIT_LOCK_EXPIRED / IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "413",
        ref = "#/components/responses/PAYLOAD_TOO_LARGE")
  })
  public ResponseEntity<ApiResponse<AppliedRequests>> apply(
      @CurrentUser AuthenticatedUser user, @PathVariable UUID spaceId, @RequestBody JsonNode body) {
    return response(
        HttpStatus.OK, requests.apply(user.userId(), spaceId, body), AppliedRequests.class);
  }

  @PostMapping(value = "/request-declines", consumes = MediaType.APPLICATION_JSON_VALUE)
  @SecurityRequirement(name = "SessionCookie")
  @SecurityRequirement(name = "CsrfToken")
  @Operation(
      summary = "소유자 선택 변경 요청 미적용",
      description =
          "현재 소유자가 유효한 대기 요청 1~20개를 함께 미적용 처리합니다. 항목 잠금은 요구하지 않고 현재 항목·원래 AI 제안의 상태는 변경하지 않습니다. 미적용 사유 입력은 지원하지 않습니다.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = @Content(schema = @Schema(implementation = DeclineBody.class)))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description =
            "CHANGE_REQUEST_STATE_CONFLICT / IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "413",
        ref = "#/components/responses/PAYLOAD_TOO_LARGE")
  })
  public ResponseEntity<ApiResponse<DeclinedRequests>> decline(
      @CurrentUser AuthenticatedUser user, @PathVariable UUID spaceId, @RequestBody JsonNode body) {
    return response(
        HttpStatus.OK, requests.decline(user.userId(), spaceId, body), DeclinedRequests.class);
  }

  @PostMapping(
      value = "/change-requests/{requestId}/cancellations",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @SecurityRequirement(name = "SessionCookie")
  @SecurityRequirement(name = "CsrfToken")
  @Operation(
      summary = "제출자 대기 요청 취소",
      description =
          "원래 제출자가 같은 참여를 유지하는 동안 해당 묶음의 대기 항목 1~20개를 취소합니다. 다른 사람의 요청이나 이미 처리·무효인 요청은 취소할 수 없습니다. 현재 항목·원래 AI 제안은 변경하지 않으며 항목 잠금·reviewToken은 요구하지 않습니다. 수정은 취소 후 새 clientRequestId로 재제출합니다.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = @Content(schema = @Schema(implementation = CancellationBody.class)))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description =
            "CHANGE_REQUEST_STATE_CONFLICT / IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "413",
        ref = "#/components/responses/PAYLOAD_TOO_LARGE")
  })
  public ResponseEntity<ApiResponse<CanceledRequests>> cancel(
      @CurrentUser AuthenticatedUser user,
      @PathVariable UUID spaceId,
      @PathVariable UUID requestId,
      @RequestBody JsonNode body) {
    return response(
        HttpStatus.OK,
        requests.cancel(user.userId(), spaceId, requestId, body),
        CanceledRequests.class);
  }

  private <T> ResponseEntity<ApiResponse<T>> response(
      HttpStatus status, JsonNode data, Class<T> type) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(mapper.treeToValue(data, type)));
  }

  @Schema(name = "ChangeRequestSubmissionBody")
  public record SubmissionBody(
      UUID clientRequestId,
      @ArraySchema(minItems = 1, maxItems = 20)
          List<ProposalDecisionController.ApplicationEntry> entries) {}

  @Schema(name = "ChangeRequestApplicationBody")
  public record ApplicationBody(
      UUID clientRequestId,
      @ArraySchema(minItems = 1, maxItems = 20) List<ApplicationEntry> entries) {}

  @Schema(name = "ChangeRequestApplicationEntry")
  public record ApplicationEntry(
      UUID requestEntryId,
      String reviewToken,
      @Schema(
              types = {"string", "null"},
              example = "1",
              description = "기존 항목 필수, 신규 생성은 생략/null")
          String expectedItemVersion,
      String leaseToken,
      @Schema(
              description =
                  "생략하면 requestedValues 사용. 전달하면 해당 동작의 입력 전체 대체. LINK_EVIDENCE는 null만 허용.")
          JsonNode values) {}

  @Schema(name = "ChangeRequestDeclineBody")
  public record DeclineBody(
      UUID clientRequestId, @ArraySchema(minItems = 1, maxItems = 20) List<DeclineEntry> entries) {}

  @Schema(name = "ChangeRequestDeclineEntry")
  public record DeclineEntry(UUID requestEntryId, String reviewToken) {}

  @Schema(name = "ChangeRequestCancellationBody")
  public record CancellationBody(
      UUID clientRequestId,
      @ArraySchema(minItems = 1, maxItems = 20) List<CancellationEntry> entries) {}

  @Schema(name = "ChangeRequestCancellationEntry")
  public record CancellationEntry(UUID requestEntryId) {}

  @Schema(name = "ChangeRequestSubmitted")
  public record SubmittedRequest(
      UUID requestId, Instant submittedAt, List<SubmittedEntry> entries) {}

  @Schema(name = "ChangeRequestSubmittedEntry")
  public record SubmittedEntry(
      UUID requestEntryId, @Schema(allowableValues = "PENDING") String status) {}

  @Schema(name = "ChangeRequestApplied")
  public record AppliedRequests(UUID batchId, Instant appliedAt, List<AppliedEntry> results) {}

  @Schema(name = "ChangeRequestAppliedEntry")
  public record AppliedEntry(UUID requestEntryId, UUID itemId, String itemVersion) {}

  @Schema(name = "ChangeRequestDeclined")
  public record DeclinedRequests(
      UUID batchId, Instant decidedAt, List<UUID> declinedRequestEntryIds) {}

  @Schema(name = "ChangeRequestCanceled")
  public record CanceledRequests(
      UUID batchId, Instant decidedAt, List<UUID> canceledRequestEntryIds) {}

  @Schema(name = "ChangeRequestPage")
  public record RequestPage(List<RequestSummary> items, String nextCursor, boolean hasNext) {}

  @Schema(name = "ChangeRequestSummary")
  public record RequestSummary(
      UUID requestId,
      Submitter submittedBy,
      Instant submittedAt,
      Counts counts,
      List<EntrySummary> entries) {}

  @Schema(name = "ChangeRequestSubmitter")
  public record Submitter(UUID membershipId, String displayName, boolean withdrawn) {}

  @Schema(name = "ChangeRequestCounts")
  public record Counts(int pending, int applied, int declined, int canceled, int invalidated) {}

  @Schema(name = "ChangeRequestEntrySummary")
  public record EntrySummary(
      UUID requestEntryId,
      ProposalAction action,
      UUID targetItemId,
      @Schema(allowableValues = {"PENDING", "APPLIED", "DECLINED", "CANCELED", "INVALIDATED"})
          String status,
      String invalidReason) {}

  @Schema(name = "ChangeRequestDetail")
  public record RequestDetail(
      UUID requestId,
      Submitter submittedBy,
      Instant submittedAt,
      Counts counts,
      List<EntryDetail> entries) {}

  @Schema(name = "ChangeRequestEntryDetail")
  public record EntryDetail(
      UUID requestEntryId,
      ProposalAction action,
      UUID targetItemId,
      @Schema(allowableValues = {"PENDING", "APPLIED", "DECLINED", "CANCELED", "INVALIDATED"})
          String status,
      String invalidReason,
      UUID proposalId,
      String expectedItemVersion,
      String currentItemVersion,
      JsonNode requestedValues,
      JsonNode currentValues,
      List<UUID> evidenceIds,
      @Schema(description = "현재 소유자·유효 PENDING에만 제공, 그 외 null") String reviewToken,
      Decision decision,
      List<String> redactedFields) {}

  @Schema(name = "ChangeRequestDecision")
  public record Decision(
      UUID batchId,
      UUID decidedByMembershipId,
      Instant decidedAt,
      UUID itemId,
      String itemVersion,
      JsonNode appliedValues) {}
}
