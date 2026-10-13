package com.hanmaum.backend.analysis.code;

import com.hanmaum.backend.global.code.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AnalysisErrorCode implements ErrorCode {
  ANALYSIS_STATE_CONFLICT("최신 분석 상태를 확인한 뒤 다시 요청해주세요.");
  private final String message;

  AnalysisErrorCode(String message) {
    this.message = message;
  }

  public HttpStatus status() {
    return HttpStatus.CONFLICT;
  }

  public String code() {
    return name();
  }

  public String message() {
    return message;
  }
}
