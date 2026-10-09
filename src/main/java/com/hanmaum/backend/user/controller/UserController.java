package com.hanmaum.backend.user.controller;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.oauth.MemberPrincipal;
import com.hanmaum.backend.user.dto.MyProfileResponse;
import com.hanmaum.backend.user.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User", description = "회원 정보")
@RestController
public class UserController {
  private final UserProfileService profiles;

  public UserController(UserProfileService profiles) {
    this.profiles = profiles;
  }

  @Operation(
      summary = "내 정보 조회",
      description = "세션으로 로그인한 본인의 기본 정보와 소셜 로그인 방식을 조회합니다. 사용자 ID를 입력하지 않습니다.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "로그인한 회원 정보"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        ref = "#/components/responses/UNAUTHENTICATED"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR")
  })
  @GetMapping(value = "/api/users/me", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ApiResponse<MyProfileResponse>> me(
      @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal principal) {
    // Provider-only sessions issued before member mapping require a fresh login.
    if (principal == null) throw new ApiException(CommonErrorCode.UNAUTHENTICATED);
    var profile =
        profiles.getMe(
            principal.getUserId(), principal.getProvider(), principal.getProviderUserId());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(profile));
  }
}
