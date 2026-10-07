package com.hanmaum.backend.global.code;

import org.springframework.http.HttpStatus;

public enum CommonErrorCode implements ErrorCode {
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, "입력 내용을 확인해주세요."),
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
  FORBIDDEN(HttpStatus.FORBIDDEN, "요청 권한 또는 CSRF 토큰을 확인해주세요."),
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다."),
  VERSION_CONFLICT(HttpStatus.CONFLICT, "변경된 내용을 다시 확인해주세요."),
  IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "같은 요청 ID를 다른 요청에 사용할 수 없습니다."),
  DELETION_IMPACT_CHANGED(HttpStatus.CONFLICT, "삭제 영향 범위가 변경되었습니다. 다시 확인해주세요."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
  AI_SERVICE_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "AI 서비스를 이용할 수 없습니다.");

  private final HttpStatus status;
  private final String message;

  CommonErrorCode(HttpStatus status, String message) {
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
