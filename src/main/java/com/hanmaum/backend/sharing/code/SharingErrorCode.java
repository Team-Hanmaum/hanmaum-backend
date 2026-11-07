package com.hanmaum.backend.sharing.code;

import com.hanmaum.backend.global.code.ErrorCode;
import org.springframework.http.HttpStatus;

public enum SharingErrorCode implements ErrorCode {
  SHARE_TARGET_UNAVAILABLE(HttpStatus.CONFLICT, "공유할 항목의 현재 상태와 변경 내용을 다시 확인해주세요.");

  private final HttpStatus status;
  private final String message;

  SharingErrorCode(HttpStatus status, String message) {
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
