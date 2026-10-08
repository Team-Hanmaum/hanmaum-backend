package com.hanmaum.backend.auth.code;

import com.hanmaum.backend.global.code.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuthErrorCode implements ErrorCode {
  OAUTH_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "소셜 로그인에 실패했습니다.");

  private final HttpStatus status;
  private final String message;

  AuthErrorCode(HttpStatus status, String message) {
    this.status = status;
    this.message = message;
  }

  @Override
  public HttpStatus status() {
    return status;
  }

  @Override
  public String code() {
    return name();
  }

  @Override
  public String message() {
    return message;
  }
}
