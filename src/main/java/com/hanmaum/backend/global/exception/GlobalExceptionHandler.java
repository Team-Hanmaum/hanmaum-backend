package com.hanmaum.backend.global.exception;

import com.hanmaum.backend.ai.client.AiServiceException;
import com.hanmaum.backend.global.response.ApiError;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return super.handleExceptionInternal(
        exception,
        ApiError.of("HTTP_" + status.value(), "요청을 처리할 수 없습니다."),
        headers,
        status,
        request);
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    Map<String, String> errors = new LinkedHashMap<>();
    exception
        .getBindingResult()
        .getFieldErrors()
        .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
    return new ResponseEntity<>(
        new ApiError("INVALID_REQUEST", "입력 내용을 확인해주세요.", errors), headers, status);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> handleUnexpectedException(Exception exception) {
    // Avoid writing source records, credentials, or upstream response bodies into logs.
    log.error("Unhandled request failure: type={}", exception.getClass().getName());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiError.of("INTERNAL_ERROR", "요청 처리 중 오류가 발생했습니다."));
  }

  @ExceptionHandler(AiServiceException.class)
  ResponseEntity<ApiError> handleAiFailure(AiServiceException exception) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(ApiError.of("AI_SERVICE_UNAVAILABLE", exception.getMessage()));
  }
}
