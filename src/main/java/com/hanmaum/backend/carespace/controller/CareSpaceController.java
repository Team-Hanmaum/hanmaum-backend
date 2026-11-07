package com.hanmaum.backend.carespace.controller;

import com.hanmaum.backend.carespace.dto.CareSpaceDetail;
import com.hanmaum.backend.carespace.dto.CareSpaceListResponse;
import com.hanmaum.backend.carespace.dto.CreateCareSpaceRequest;
import com.hanmaum.backend.carespace.dto.CreateCareSpaceResponse;
import com.hanmaum.backend.carespace.dto.UpdateCareSpaceResponse;
import com.hanmaum.backend.carespace.service.CareSpaceCreationService;
import com.hanmaum.backend.carespace.service.CareSpaceInformationService;
import com.hanmaum.backend.carespace.service.CareSpaceQueryService;
import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.AuthenticatedUser;
import com.hanmaum.backend.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(value = "/api/spaces", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "CareSpace", description = "돌봄 공간 생성·조회, 초대·참여, 구성원·소유권 관리")
public class CareSpaceController {
  private final CareSpaceCreationService creation;
  private final CareSpaceQueryService queries;
  private final CareSpaceInformationService information;

  public CareSpaceController(
      CareSpaceCreationService creation,
      CareSpaceQueryService queries,
      CareSpaceInformationService information) {
    this.creation = creation;
    this.queries = queries;
    this.information = information;
  }

  @PatchMapping(value = "/{spaceId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "돌봄 공간 정보 수정",
      description =
          "현재 소유자가 조회한 공간 버전으로 호칭을 변경합니다. 생성과 동일한 정규화·중복 검사를 사용합니다. 같은 호칭은 버전·수정 시각을 유지하고 동일 요청은 최초 결과를 반환합니다. 구성원은 DIRECT_INPUT UPDATE_SPACE로 요청합니다.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      required = true,
      content = @Content(schema = @Schema(implementation = UpdateBody.class)))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "공간 정보 수정 완료 또는 동일 요청 결과"),
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
        ref = "#/components/responses/UPDATE_CARE_SPACE_CONFLICT"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR")
  })
  public ResponseEntity<ApiResponse<UpdateCareSpaceResponse>> update(
      @CurrentUser AuthenticatedUser user, @PathVariable UUID spaceId, @RequestBody JsonNode body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(information.update(user.userId(), spaceId, body)));
  }

  @Schema(name = "CareSpaceUpdateBody")
  public record UpdateBody(
      @Schema(description = "앞뒤 Unicode 공백 제거 후 1~30 코드 포인트. 줄바꿈 불허") String subjectLabel,
      @Schema(example = "3", description = "조회한 공간의 1 이상 십진 정수 문자열 버전") String expectedSpaceVersion,
      UUID clientRequestId) {}

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
