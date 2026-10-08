package com.hanmaum.backend.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Auth", description = "세션·CSRF 인증 기반")
public class CsrfController {
  @Operation(
      summary = "CSRF 토큰 발급",
      description =
          "로그인 없이 호출할 수 있습니다. 반환된 헤더 이름과 토큰을 변경 요청에 사용하고 로그인·로그아웃 후 다시 발급받습니다. 공통 응답으로 감싸지 않습니다.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "세션에 연결된 CSRF 토큰",
        headers =
            @Header(
                name = "Cache-Control",
                schema = @Schema(type = "string", allowableValues = "no-store")),
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = CsrfResponse.class))),
    @ApiResponse(responseCode = "500", ref = "#/components/responses/INTERNAL_ERROR")
  })
  @GetMapping("/api/auth/csrf")
  public ResponseEntity<CsrfResponse> csrf(@Parameter(hidden = true) CsrfToken token) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(new CsrfResponse(token.getHeaderName(), token.getToken()));
  }

  public record CsrfResponse(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "X-CSRF-TOKEN")
          String headerName,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "현재 세션에 연결된 CSRF 토큰")
          String token) {}
}
