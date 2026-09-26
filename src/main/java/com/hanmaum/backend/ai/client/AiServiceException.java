package com.hanmaum.backend.ai.client;

public class AiServiceException extends RuntimeException {
  public AiServiceException() {
    super("AI 분석 응답을 처리할 수 없습니다.");
  }
}
