package com.hanmaum.backend.carespace.controller;

import com.hanmaum.backend.carespace.dto.DepartureResponse;
import com.hanmaum.backend.carespace.dto.MembershipChangeRequest;
import com.hanmaum.backend.carespace.dto.RemoveMembershipResponse;
import com.hanmaum.backend.carespace.dto.TransferOwnershipRequest;
import com.hanmaum.backend.carespace.dto.TransferOwnershipResponse;
import com.hanmaum.backend.carespace.service.MembershipLifecycleService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/spaces/{spaceId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "CareSpace")
public class MembershipLifecycleController {
  private final MembershipLifecycleService lifecycle;

  public MembershipLifecycleController(MembershipLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @Operation(
      summary = "소유권 이전",
      description =
          "현재 다른 구성원에게 즉시 이전합니다. 성공 재전송은 원래 참여가 유지되면 최초 결과를 반환합니다. 현재 역할은 공간 상세를 다시 조회하세요.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "처리 완료 또는 최초 성공 결과 재전달"),
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
        ref = "#/components/responses/OWNERSHIP_TRANSFER_CONFLICT"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR")
  })
  @PostMapping(value = "/ownership-transfers", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<TransferOwnershipResponse>> transfer(
      @CurrentUser AuthenticatedUser user,
      @PathVariable UUID spaceId,
      @Valid @RequestBody TransferOwnershipRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(lifecycle.transfer(user.userId(), spaceId, request)));
  }

  @Operation(
      summary = "돌봄 공간 나가기",
      description =
          "소유자는 먼저 소유권을 이전해야 합니다. 성공 재전송은 공간 접근 종료 후에도 본인의 종료 참여 ID·시각만 반환합니다. 미완료 담당 할 일은 미지정으로 전환하고 편집 잠금을 해제합니다.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "처리 완료 또는 최초 성공 결과 재전달"),
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
        ref = "#/components/responses/SPACE_DEPARTURE_CONFLICT"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR")
  })
  @PostMapping(value = "/departures", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<DepartureResponse>> depart(
      @CurrentUser AuthenticatedUser user,
      @PathVariable UUID spaceId,
      @Valid @RequestBody MembershipChangeRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(lifecycle.depart(user.userId(), spaceId, request)));
  }

  @Operation(
      summary = "구성원 내보내기",
      description =
          "현재 소유자가 다른 구성원을 내보냅니다. 성공 재전송에도 현재 소유권과 원래 참여를 검증합니다. 미완료 담당 할 일은 미지정으로 전환하고 편집 잠금을 해제합니다.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "처리 완료 또는 최초 성공 결과 재전달"),
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
        ref = "#/components/responses/MEMBERSHIP_REMOVAL_CONFLICT"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR")
  })
  @PostMapping(
      value = "/memberships/{membershipId}/removals",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<RemoveMembershipResponse>> remove(
      @CurrentUser AuthenticatedUser user,
      @PathVariable UUID spaceId,
      @PathVariable UUID membershipId,
      @Valid @RequestBody MembershipChangeRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(lifecycle.remove(user.userId(), spaceId, membershipId, request)));
  }
}
