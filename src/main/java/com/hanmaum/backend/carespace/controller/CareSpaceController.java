package com.hanmaum.backend.carespace.controller;

import com.hanmaum.backend.carespace.dto.CareSpaceDetail;
import com.hanmaum.backend.carespace.dto.CareSpaceListResponse;
import com.hanmaum.backend.carespace.dto.CreateCareSpaceRequest;
import com.hanmaum.backend.carespace.dto.CreateCareSpaceResponse;
import com.hanmaum.backend.carespace.service.CareSpaceCreationService;
import com.hanmaum.backend.carespace.service.CareSpaceQueryService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/spaces", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "CareSpace", description = "돌봄 공간 생성·참여 공간 조회")
public class CareSpaceController {
  private final CareSpaceCreationService creation;
  private final CareSpaceQueryService queries;

  public CareSpaceController(CareSpaceCreationService creation, CareSpaceQueryService queries) {
    this.creation = creation;
    this.queries = queries;
  }

  @Operation(
      summary = "돌봄 공간 생성",
      description =
          "공간과 생성자의 소유자 참여를 함께 생성합니다. 현재 소유한 공간과 같은 호칭은 불가합니다. "
              + "동일 clientRequestId와 동일 내용의 성공 재요청은 기존 결과를 201로 반환합니다.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "생성 완료 또는 성공 요청 재전송"),
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
        ref = "#/components/responses/CREATE_CARE_SPACE_CONFLICT"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR")
  })
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<CreateCareSpaceResponse>> create(
      @CurrentUser AuthenticatedUser currentUser,
      @Valid @RequestBody CreateCareSpaceRequest request) {
    var result = creation.create(currentUser.userId(), request);
    return ResponseEntity.created(URI.create("/api/spaces/" + result.spaceId()))
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(result));
  }

  @Operation(
      summary = "참여 공간 목록 조회",
      description = "현재 참여 중인 활성 공간 전체를 참여 일시·공간 ID 오름차순으로 조회합니다. 페이지네이션은 없습니다.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "참여 공간 목록. 없으면 items는 빈 배열"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        ref = "#/components/responses/UNAUTHENTICATED"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR")
  })
  @GetMapping
  public ResponseEntity<ApiResponse<CareSpaceListResponse>> list(
      @CurrentUser AuthenticatedUser currentUser) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(queries.list(currentUser.userId())));
  }

  @Operation(
      summary = "돌봄 공간 상세 조회",
      description = "현재 참여자만 기본 정보와 공간 버전을 조회할 수 있습니다.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "공간 기본 정보"),
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
  @GetMapping("/{spaceId}")
  public ResponseEntity<ApiResponse<CareSpaceDetail>> detail(
      @CurrentUser AuthenticatedUser currentUser, @PathVariable UUID spaceId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(queries.detail(currentUser.userId(), spaceId)));
  }
}
