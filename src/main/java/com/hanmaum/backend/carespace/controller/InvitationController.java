package com.hanmaum.backend.carespace.controller;

import com.hanmaum.backend.carespace.dto.CreateInvitationResponse;
import com.hanmaum.backend.carespace.dto.InvitationPreview;
import com.hanmaum.backend.carespace.dto.InvitationRequest;
import com.hanmaum.backend.carespace.dto.JoinInvitationResponse;
import com.hanmaum.backend.carespace.dto.SpaceMemberListResponse;
import com.hanmaum.backend.carespace.service.InvitationService;
import com.hanmaum.backend.carespace.service.SpaceMemberQueryService;
import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.AuthenticatedUser;
import com.hanmaum.backend.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "CareSpace", description = "돌봄 공간·초대·참여·구성원")
public class InvitationController {
  private final InvitationService invitations;
  private final SpaceMemberQueryService members;

  public InvitationController(InvitationService invitations, SpaceMemberQueryService members) {
    this.invitations = invitations;
    this.members = members;
  }

  @Operation(
      summary = "가족 초대 링크 생성",
      description = "현재 소유자·구성원이 24시간 유효한 링크를 발급합니다. 동일 요청은 기존 링크를 반환하며 만료 시각을 연장하지 않습니다.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "링크 발급 또는 성공 발급 결과 재전달"),
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
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        ref = "#/components/responses/RESOURCE_NOT_FOUND"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        ref = "#/components/responses/INVITATION_COMMAND_CONFLICT")
  })
  @PostMapping(
      value = "/api/spaces/{spaceId}/invitations",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<CreateInvitationResponse>> issue(
      @CurrentUser AuthenticatedUser user,
      @PathVariable UUID spaceId,
      @Valid @RequestBody InvitationRequest request) {
    return ResponseEntity.status(201)
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.success(
                invitations.issue(user.userId(), spaceId, request.clientRequestId())));
  }

  @Operation(
      summary = "초대 링크 확인",
      description = "로그인 후 유효성과 현재 참여 여부만 조회합니다. 이름·돌봄 내용은 반환하지 않습니다.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "유효한 초대와 현재 참여 여부"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        ref = "#/components/responses/UNAUTHENTICATED"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        ref = "#/components/responses/INVITATION_UNAVAILABLE"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "410",
        ref = "#/components/responses/INVITATION_EXPIRED"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR")
  })
  @GetMapping("/api/invitations/{invitationToken}")
  public ResponseEntity<ApiResponse<InvitationPreview>> preview(
      @CurrentUser AuthenticatedUser user, @PathVariable String invitationToken) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(invitations.preview(user.userId(), invitationToken)));
  }

  @Operation(
      summary = "초대 링크로 공간 참여",
      description = "신규 참여는 201, 기존 참여 확인은 200입니다. 같은 요청의 성공 재전송은 최초 상태 코드를 유지하고 현재 참여 권한을 검사합니다.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "새 참여 등록 또는 같은 성공 결과 재전달"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "현재 참여 정보 또는 같은 성공 결과 재전달"),
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
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        ref = "#/components/responses/INVITATION_UNAVAILABLE"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        ref = "#/components/responses/INVITATION_COMMAND_CONFLICT"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "410",
        ref = "#/components/responses/INVITATION_EXPIRED")
  })
  @PostMapping(
      value = "/api/invitations/{invitationToken}/join",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<JoinInvitationResponse>> join(
      @CurrentUser AuthenticatedUser user,
      @PathVariable String invitationToken,
      @Valid @RequestBody InvitationRequest request) {
    var result = invitations.join(user.userId(), invitationToken, request.clientRequestId());
    return ResponseEntity.status(result.created() ? 201 : 200)
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(result.response()));
  }

  @Operation(
      summary = "구성원 목록 조회",
      description = "현재 참여자 전체를 소유자 우선·참여 시각·참여 ID 순서로 반환합니다. 이름 null 허용, 담당 할 일 개수는 제외합니다.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "현재 구성원 전체 목록"),
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
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        ref = "#/components/responses/RESOURCE_NOT_FOUND")
  })
  @GetMapping("/api/spaces/{spaceId}/members")
  public ResponseEntity<ApiResponse<SpaceMemberListResponse>> members(
      @CurrentUser AuthenticatedUser user, @PathVariable UUID spaceId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(members.list(user.userId(), spaceId)));
  }
}
