package com.hanmaum.backend.dashboard.controller;

import com.hanmaum.backend.dashboard.dto.DashboardResponse;
import com.hanmaum.backend.dashboard.service.DashboardService;
import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Dashboard", description = "공간 홈의 돌봄 요약")
@SecurityRequirement(name = "SessionCookie")
public class DashboardController {
  private final DashboardService dashboard;

  public DashboardController(DashboardService dashboard) {
    this.dashboard = dashboard;
  }

  @GetMapping(
      value = "/api/spaces/{spaceId}/dashboard",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "돌봄 현황판 조회",
      description =
          "현재 참여자가 같은 조회 시점의 예정 일정 2건, 지난 일정 건수, 남은 할 일 3건, 미확인 질문 2건, 최근 확정 변경 3건과 각 전체 건수를 조회합니다.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "현황판 조회 성공"),
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
  public ResponseEntity<ApiResponse<DashboardResponse>> get(
      @CurrentUser AuthenticatedUser actor, @PathVariable UUID spaceId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(dashboard.get(actor.userId(), spaceId)));
  }
}
