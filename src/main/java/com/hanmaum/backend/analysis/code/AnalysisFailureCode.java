package com.hanmaum.backend.analysis.code;

/** Persisted job failures, not HTTP response codes. Never persist provider messages. */
public enum AnalysisFailureCode {
  AI_INTEGRATION_ERROR("AI 연동 요청을 처리할 수 없습니다."),
  AI_DUPLICATE_REQUEST("이미 접수된 분석 요청입니다."),
  AI_REQUEST_ID_REUSED("분석 요청 ID가 다른 요청에 사용되었습니다."),
  AI_INVALID_RESPONSE("AI 분석 결과를 검증하지 못했습니다."),
  AI_RECEIPT_STORE_UNAVAILABLE("AI 요청 접수 기록을 확인할 수 없습니다."),
  AI_TIMEOUT("AI 분석 응답 시간이 초과되었습니다."),
  AI_SERVICE_UNAVAILABLE("AI 서비스를 이용할 수 없습니다."),
  ANALYSIS_INTERRUPTED("분석 실행이 중단되었습니다. 다시 시도해주세요."),
  ANALYSIS_REQUESTER_INACTIVE("분석 요청자의 참여가 종료되었습니다. 현재 참여자가 다시 요청해주세요."),
  ANALYSIS_DEPENDENCY_UNAVAILABLE("기존 항목 분석 연결이 준비되지 않았습니다."),
  ANALYSIS_STORAGE_FAILED("분석 결과를 저장하지 못했습니다. 다시 시도해주세요.");
  private final String message;

  AnalysisFailureCode(String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }
}
