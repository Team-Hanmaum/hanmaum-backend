package com.hanmaum.backend.record.controller;

import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.AuthenticatedUser;
import com.hanmaum.backend.global.security.CurrentUser;
import com.hanmaum.backend.record.dto.CreateSourceRecordRequest;
import com.hanmaum.backend.record.dto.CreateSourceRecordResponse;
import com.hanmaum.backend.record.dto.DeleteSourceRecordRequest;
import com.hanmaum.backend.record.dto.DeleteSourceRecordResponse;
import com.hanmaum.backend.record.dto.SourceRecordDeletionImpact;
import com.hanmaum.backend.record.dto.SourceRecordDetail;
import com.hanmaum.backend.record.dto.SourceRecordListResponse;
import com.hanmaum.backend.record.service.SourceRecordCreationService;
import com.hanmaum.backend.record.service.SourceRecordDeletionImpactService;
import com.hanmaum.backend.record.service.SourceRecordDeletionService;
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
@Tag(name = "Record", description = "소식 저장·분석 접수·목록·상세 및 원본 삭제 영향 조회")
public class SourceRecordController {
  private final SourceRecordCreationService creation;
  private final SourceRecordQueryService queries;
  private final SourceRecordDeletionImpactService deletionImpact;
  private final SourceRecordDeletionService deletion;

  public SourceRecordController(
      SourceRecordCreationService creation,
      SourceRecordQueryService queries,
      SourceRecordDeletionImpactService deletionImpact,
      SourceRecordDeletionService deletion) {
    this.creation = creation;
    this.queries = queries;
    this.deletionImpact = deletionImpact;
    this.deletion = deletion;
  }

  @Operation(
      summary = "소식 원본 저장",
      description =
          "현재 참여자는 소식을 작성할 수 있습니다. 원문·작성자·등록 시각을 함께 저장하며 공간 버전은 바꾸지 않습니다. "
              + "최초 분석 작업을 함께 QUEUED로 접수합니다. 동일 clientRequestId의 동일 요청은 기존 접수 결과를 201로 반환합니다. 최신 분석 상태는 조회 API에서 확인합니다.",
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
              + "원문 전체와 최신 분석 상태를 반환합니다. 분석 도입 전 원본의 latestAnalysis는 null입니다.",
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
              + "canDelete는 현재 삭제 권한 안내입니다. 최신 분석 상태와 완료된 실행의 유효 proposalIds를 반환합니다.",
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

  @Operation(
      summary = "소식 원본 삭제 영향 확인",
      description =
          "현재 소유자 또는 현재 참여 중인 원본 작성자가 조회할 수 있습니다. "
              + "원본에 의존하는 제안·근거·항목·이력을 중복 없이 집계하고 영향 확인값을 반환합니다. "
              + "원본과 파생 본문은 응답에 포함되지 않으며 저장된 상태를 변경하지 않습니다.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "소식 원본 삭제 영향"),
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
  @GetMapping("/{recordId}/deletion-impact")
  public ResponseEntity<ApiResponse<SourceRecordDeletionImpact>> deletionImpact(
      @CurrentUser AuthenticatedUser currentUser,
      @PathVariable UUID spaceId,
      @PathVariable UUID recordId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(deletionImpact.preview(currentUser.userId(), spaceId, recordId)));
  }

  @Operation(
      summary = "소식 원본 삭제",
      description =
          "현재 소유자 또는 현재 참여 중인 원본 작성자가 영향 확인 후 원본과 연결된 내용을 삭제합니다. "
              + "영향이 바뀌면 다시 조회해야 합니다. 같은 요청 ID와 본문은 처리 결과를 재사용합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "원본과 연결 내용의 삭제 완료"),
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
        ref = "#/components/responses/DELETE_SOURCE_RECORD_CONFLICT"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR")
  })
  @PostMapping(value = "/{recordId}/deletions", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<DeleteSourceRecordResponse>> delete(
      @CurrentUser AuthenticatedUser currentUser,
      @PathVariable UUID spaceId,
      @PathVariable UUID recordId,
      @Valid @RequestBody DeleteSourceRecordRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.success(deletion.delete(currentUser.userId(), spaceId, recordId, request)));
  }
}
