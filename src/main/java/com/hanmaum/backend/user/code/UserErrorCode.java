package com.hanmaum.backend.user.code;

import com.hanmaum.backend.global.code.ErrorCode;
import org.springframework.http.HttpStatus;

public enum UserErrorCode implements ErrorCode {
  OWNED_SPACE_REMAINS(HttpStatus.CONFLICT, "소유 중인 모든 공간의 소유권을 이전하거나 공간을 삭제한 뒤 탈퇴해주세요.");
  private final HttpStatus status;
  private final String message;

  UserErrorCode(HttpStatus status, String message) {
    this.status = status;
    this.message = message;
  }

  @Override
  public HttpStatus status() {
    return status;
  }

  @Override
  public String message() {
    return message;
  }

  @Override
  public String code() {
    return name();
  }
}
