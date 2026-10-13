package com.hanmaum.backend.analysis.controller;

import com.hanmaum.backend.analysis.dto.AnalysisAccepted;
import com.hanmaum.backend.analysis.dto.AnalysisDetail;
import com.hanmaum.backend.analysis.dto.RetryAnalysisRequest;
import com.hanmaum.backend.analysis.service.AnalysisQueryService;
import com.hanmaum.backend.analysis.service.AnalysisSubmissionService;
import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.AuthenticatedUser;
import com.hanmaum.backend.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(
    value = "/api/spaces/{spaceId}/source-records/{recordId}/analyses",
    produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Analysis", description = "분석 상태 조회·실패 재시도. 완료는 제안 저장이며 항목 반영과 별개")
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
public class AnalysisController {
  private final AnalysisQueryService queries;
  private final AnalysisSubmissionService submissions;

  public AnalysisController(AnalysisQueryService queries, AnalysisSubmissionService submissions) {
    this.queries = queries;
    this.submissions = submissions;
  }

  @GetMapping("/{analysisId}")
  @Operation(
      summary = "소식 분석 상태 조회",
      description =
          "현재 참여자가 조회합니다. FAILED 상태도 조회 성공은 200 SUCCESS이며 failure에 원인을 제공합니다. 과거 분석 이력이 없는 소식에는 ID를 만들지 않습니다.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "분석 상태 조회 완료")
  public ResponseEntity<ApiResponse<AnalysisDetail>> get(
      @CurrentUser AuthenticatedUser user,
      @PathVariable UUID spaceId,
      @PathVariable UUID recordId,
      @PathVariable UUID analysisId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(queries.find(user.userId(), spaceId, recordId, analysisId)));
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "실패한 소식 분석 재시도",
      description =
          "현재 참여자가 최신 FAILED 실행만 재시도합니다. 접수 성공은 202 QUEUED이며 실제 실행 결과는 GET으로 확인합니다. 동일 요청 재전송은 같은 실행 ID를 반환합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "202",
        description = "분석 접수 완료"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        ref = "#/components/responses/RETRY_ANALYSIS_CONFLICT")
  })
  public ResponseEntity<ApiResponse<AnalysisAccepted>> retry(
      @CurrentUser AuthenticatedUser user,
      @PathVariable UUID spaceId,
      @PathVariable UUID recordId,
      @Valid @RequestBody RetryAnalysisRequest request) {
    var result = submissions.retry(user.userId(), spaceId, recordId, request);
    return ResponseEntity.accepted()
        .location(
            URI.create(
                "/api/spaces/"
                    + spaceId
                    + "/source-records/"
                    + recordId
                    + "/analyses/"
                    + result.analysisId()))
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.accepted(result));
  }
}
