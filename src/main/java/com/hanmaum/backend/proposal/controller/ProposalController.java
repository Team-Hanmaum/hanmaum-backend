package com.hanmaum.backend.proposal.controller;

import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.AuthenticatedUser;
import com.hanmaum.backend.global.security.CurrentUser;
import com.hanmaum.backend.proposal.dto.ProposalDetail;
import com.hanmaum.backend.proposal.dto.ProposalListResponse;
import com.hanmaum.backend.proposal.service.ProposalQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    value = "/api/spaces/{spaceId}/proposals",
    produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Proposal", description = "AI 제안 조회·검토 및 소유자 일괄 반영/미적용")
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
public class ProposalController {
  private final ProposalQueryService queries;

  public ProposalController(ProposalQueryService queries) {
    this.queries = queries;
  }

  @GetMapping
  @Operation(
      summary = "AI 제안 목록 조회",
      description =
          "현재 참여자만 조회합니다. 생성 시각·ID 내림차순, 기본 20개·최대 50개입니다. "
              + "상태 생략 시 REVIEW_PENDING입니다. INVALIDATED는 제목 없이 최소 정보만 반환합니다. "
              + "다음 페이지는 같은 공간·recordId·status에서 nextCursor를 사용하세요. 조회는 상태를 변경하지 않습니다.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "제안 목록. 마지막 페이지 nextCursor=null, hasNext=false")
  public ResponseEntity<ApiResponse<ProposalListResponse>> list(
      @CurrentUser AuthenticatedUser currentUser,
      @PathVariable UUID spaceId,
      @Parameter(description = "같은 공간의 원본 ID. 생략하면 공간 전체", schema = @Schema(format = "uuid"))
          @RequestParam(required = false)
          String recordId,
      @Parameter(
              schema =
                  @Schema(
                      allowableValues = {"REVIEW_PENDING", "INVALIDATED", "APPLIED", "DECLINED"}))
          @RequestParam(required = false)
          String status,
      @Parameter(description = "직전 응답의 nextCursor. 첫 조회에서는 생략") @RequestParam(required = false)
          String cursor,
      @Parameter(schema = @Schema(minimum = "1", maximum = "50", defaultValue = "20"))
          @RequestParam(defaultValue = "20")
          int size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.success(
                queries.list(currentUser.userId(), spaceId, recordId, status, cursor, size)));
  }

  @GetMapping("/{proposalId}")
  @Operation(
      summary = "AI 제안 상세 및 근거 조회",
      description =
          "현재 참여자만 조회합니다. 제안 값은 유형별 공개 필드로 변환하며 알 수 없는 값은 null입니다. "
              + "유효한 미처리 제안에 reviewToken을 제공합니다. CREATE에는 기존 대상이 없습니다. "
              + "v2 proposedValues는 변경분이며 changedFields·clearFields로 설정·삭제를 구분합니다. "
              + "v2는 관계·검토 사유·비교 근거를 제공합니다. 입력 의존성이 무효이면 현재 값·제안 값·근거를 노출하지 않습니다.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "제안 상세 또는 내용이 제거된 INVALIDATED 제안")
  public ResponseEntity<ApiResponse<ProposalDetail>> detail(
      @CurrentUser AuthenticatedUser currentUser,
      @PathVariable UUID spaceId,
      @PathVariable UUID proposalId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(queries.detail(currentUser.userId(), spaceId, proposalId)));
  }
}
