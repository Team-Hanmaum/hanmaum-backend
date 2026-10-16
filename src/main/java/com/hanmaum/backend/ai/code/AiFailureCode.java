package com.hanmaum.backend.ai.code;

/** Internal adapter outcomes, not public HTTP errors or persisted execution states. */
public enum AiFailureCode {
  AI_INTEGRATION_ERROR("AI 연동 요청을 처리할 수 없습니다."),
  AI_DUPLICATE_REQUEST("이미 접수된 분석 요청입니다."),
  AI_REQUEST_ID_REUSED("분석 요청 ID가 다른 요청에 사용되었습니다."),
  AI_INVALID_RESPONSE("AI 분석 결과를 검증하지 못했습니다."),
  AI_SERVICE_UNAVAILABLE("AI 서비스를 이용할 수 없습니다."),
  AI_RECEIPT_STORE_UNAVAILABLE("AI 요청 접수 기록을 확인할 수 없습니다."),
  AI_TIMEOUT("AI 분석 응답 시간이 초과되었습니다.");

  private final String message;

  AiFailureCode(String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }
}
