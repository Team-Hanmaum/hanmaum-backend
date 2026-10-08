package com.hanmaum.backend.global.code;

/** Success body codes. Controllers select HTTP status; 204 has no response body. */
public enum SuccessCode {
  SUCCESS("요청이 완료되었습니다."),
  ACCEPTED("요청이 접수되었습니다.");

  private final String message;

  SuccessCode(String message) {
    this.message = message;
  }

  public String code() {
    return name();
  }

  public String message() {
    return message;
  }
}
