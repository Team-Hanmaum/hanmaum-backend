package com.hanmaum.backend.record.controller;

import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.AuthenticatedUser;
import com.hanmaum.backend.global.security.CurrentUser;
import com.hanmaum.backend.record.dto.CreateSourceRecordRequest;
import com.hanmaum.backend.record.dto.CreateSourceRecordResponse;
import com.hanmaum.backend.record.dto.SourceRecordDetail;
import com.hanmaum.backend.record.dto.SourceRecordListResponse;
import com.hanmaum.backend.record.service.SourceRecordCreationService;
import com.hanmaum.backend.record.service.SourceRecordQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    value = "/api/spaces/{spaceId}/source-records",
    produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Record", description = "소식 원본 저장·목록·상세 조회. 분석 실행·원본 삭제는 후속 구현")
public class SourceRecordController {
  private final SourceRecordCreationService creation;
  private final SourceRecordQueryService queries;

  public SourceRecordController(
      SourceRecordCreationService creation, SourceRecordQueryService queries) {
    this.creation = creation;
    this.queries = queries;
  }

  @Operation(
      summary = "소식 원본 저장",
      description =
          "현재 참여자는 소식을 작성할 수 있습니다. 원문·작성자·등록 시각을 함께 저장하며 공간 버전은 바꾸지 않습니다. "
              + "동일 clientRequestId의 동일 요청은 기존 결과를 201로 반환합니다. 현재 분석 미연결로 analysis는 null입니다.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "저장 완료 또는 성공 요청 재전송"),
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
        ref = "#/components/responses/CREATE_SOURCE_RECORD_CONFLICT"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR")
  })
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<CreateSourceRecordResponse>> create(
      @CurrentUser AuthenticatedUser currentUser,
      @PathVariable UUID spaceId,
      @Valid @RequestBody CreateSourceRecordRequest request) {
    var result = creation.create(currentUser.userId(), spaceId, request);
    return ResponseEntity.created(
            URI.create("/api/spaces/" + spaceId + "/source-records/" + result.recordId()))
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(result));
  }

  @Operation(
      summary = "소식 목록 조회",
      description =
          "현재 참여자만 조회할 수 있습니다. 등록 시각·ID 내림차순이며 기본 20개, 최대 50개입니다. "
              + "nextCursor를 다음 요청의 cursor에 그대로 전달하세요. 새 소식은 목록을 새로 조회하여 확인합니다. "
              + "원문 전체를 반환하며 현재 latestAnalysis는 null입니다.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "소식 목록. 마지막 페이지 nextCursor=null, hasNext=false"),
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
  @GetMapping
  public ResponseEntity<ApiResponse<SourceRecordListResponse>> list(
      @CurrentUser AuthenticatedUser currentUser,
      @PathVariable UUID spaceId,
      @Parameter(description = "직전 응답의 nextCursor. 첫 조회에서는 생략") @RequestParam(required = false)
          String cursor,
      @Parameter(schema = @Schema(minimum = "1", maximum = "50", defaultValue = "20"))
          @RequestParam(defaultValue = "20")
          int size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(queries.list(currentUser.userId(), spaceId, cursor, size)));
  }

  @Operation(
      summary = "소식 상세 조회",
      description =
          "같은 공간의 삭제되지 않은 원본만 조회합니다. 작성자의 참여 종료는 원본 삭제가 아닙니다. "
              + "canDelete는 현재 삭제 권한 안내이며 삭제 API는 아직 없습니다. latestAnalysis=null, proposalIds=[]를 반환합니다.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "소식 원본 상세"),
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
  @GetMapping("/{recordId}")
  public ResponseEntity<ApiResponse<SourceRecordDetail>> detail(
      @CurrentUser AuthenticatedUser currentUser,
      @PathVariable UUID spaceId,
      @PathVariable UUID recordId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(queries.detail(currentUser.userId(), spaceId, recordId)));
  }
}
