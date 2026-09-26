package com.hanmaum.backend.auth.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {
  @GetMapping("/api/auth/csrf")
  public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(new CsrfResponse(token.getHeaderName(), token.getToken()));
  }

  public record CsrfResponse(String headerName, String token) {}
}
