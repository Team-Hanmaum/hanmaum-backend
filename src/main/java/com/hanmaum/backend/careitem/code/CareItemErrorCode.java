package com.hanmaum.backend.careitem.code;

import com.hanmaum.backend.global.code.ErrorCode;
import org.springframework.http.HttpStatus;

public enum CareItemErrorCode implements ErrorCode {
  ITEM_LOCKED(HttpStatus.CONFLICT, "다른 사용자가 해당 항목을 편집 중입니다.");

  private final HttpStatus status;
  private final String message;

  CareItemErrorCode(HttpStatus status, String message) {
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
