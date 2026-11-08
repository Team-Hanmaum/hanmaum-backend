package com.hanmaum.backend.user.controller;

import com.hanmaum.backend.global.security.*;
import com.hanmaum.backend.user.service.MemberWithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.*;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@Tag(name = "User")
public class MemberWithdrawalController {
  private final MemberWithdrawalService withdrawal;
  private final WithdrawalSessionCleanup sessions;

  public MemberWithdrawalController(
      MemberWithdrawalService withdrawal, WithdrawalSessionCleanup sessions) {
    this.withdrawal = withdrawal;
    this.sessions = sessions;
  }

  @PostMapping(value = "/api/users/me/withdrawals", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "회원 탈퇴",
      description =
          "소유 공간이 없어야 합니다. 계정·소셜 연결·전체 세션을 제거하고 공동 기록은 탈퇴 작성자로 보존합니다. 미완료 담당/잠금 해제와 대기 요청 무효화 후 204, 이전 인증의 재요청은 401입니다.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      required = true,
      content = @Content(schema = @Schema(implementation = Body.class)))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "204",
        description = "계정·인증 정리 완료. 본문 없음",
        content = @Content),
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
        responseCode = "409",
        ref = "#/components/responses/MEMBER_WITHDRAWAL_CONFLICT"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "413",
        ref = "#/components/responses/PAYLOAD_TOO_LARGE"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR")
  })
  public ResponseEntity<Void> withdraw(
      @CurrentUser AuthenticatedUser user,
      @RequestBody JsonNode body,
      HttpServletRequest request,
      HttpServletResponse response) {
    withdrawal.withdraw(user.userId(), body);
    sessions.clear(user.userId(), request, response);
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  @Schema(name = "MemberWithdrawalBody")
  public record Body(UUID clientRequestId) {}
}
