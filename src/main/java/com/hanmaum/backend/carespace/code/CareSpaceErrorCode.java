package com.hanmaum.backend.carespace.code;

import com.hanmaum.backend.global.code.ErrorCode;
import org.springframework.http.HttpStatus;

public enum CareSpaceErrorCode implements ErrorCode {
  SPACE_LABEL_DUPLICATED(HttpStatus.CONFLICT, "같은 호칭의 돌봄 공간을 이미 소유하고 있습니다.");

  private final HttpStatus status;
  private final String message;

  CareSpaceErrorCode(HttpStatus status, String message) {
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
