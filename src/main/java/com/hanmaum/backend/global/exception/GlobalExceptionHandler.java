package com.hanmaum.backend.global.exception;

import com.hanmaum.backend.ai.client.AiServiceException;
import com.hanmaum.backend.global.response.ApiFieldError;
import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.response.ErrorCode;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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
        body instanceof ApiResponse<?> ? body : ApiResponse.error(status),
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
    var errors =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> new ApiFieldError(error.getField(), reason(error.getDefaultMessage())))
            .toList();
    return handleExceptionInternal(
        exception, ApiResponse.error(ErrorCode.INVALID_REQUEST, errors), headers, status, request);
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    if (exception.isForReturnValue()) {
      return handleExceptionInternal(exception, null, headers, status, request);
    }
    var errors =
        exception.getParameterValidationResults().stream()
            .flatMap(
                result -> {
                  if (result instanceof ParameterErrors parameterErrors) {
                    return parameterErrors.getFieldErrors().stream()
                        .map(
                            error ->
                                new ApiFieldError(
                                    error.getField(), reason(error.getDefaultMessage())));
                  }
                  String field = parameterName(result.getMethodParameter());
                  return field == null
                      ? Stream.<ApiFieldError>empty()
                      : result.getResolvableErrors().stream()
                          .map(
                              error -> new ApiFieldError(field, reason(error.getDefaultMessage())));
                })
            .toList();
    return handleExceptionInternal(
        exception, ApiResponse.error(ErrorCode.INVALID_REQUEST, errors), headers, status, request);
  }

  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    var errors =
        exception instanceof MethodArgumentTypeMismatchException mismatch
            ? List.of(new ApiFieldError(mismatch.getName(), "입력 형식을 확인해주세요."))
            : List.<ApiFieldError>of();
    return handleExceptionInternal(
        exception, ApiResponse.error(ErrorCode.INVALID_REQUEST, errors), headers, status, request);
  }

  @Override
  protected ResponseEntity<Object> handleMissingServletRequestParameter(
      MissingServletRequestParameterException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    var errors = List.of(new ApiFieldError(exception.getParameterName(), "필수 입력입니다."));
    return handleExceptionInternal(
        exception, ApiResponse.error(ErrorCode.INVALID_REQUEST, errors), headers, status, request);
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiResponse<Void>> handleApiException(ApiException exception) {
    return ResponseEntity.status(exception.code().status())
        .body(ApiResponse.error(exception.code(), exception.errors()));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException exception) {
    return ResponseEntity.status(ErrorCode.FORBIDDEN.status())
        .body(ApiResponse.error(ErrorCode.FORBIDDEN));
  }

  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException exception) {
    return ResponseEntity.status(ErrorCode.UNAUTHENTICATED.status())
        .body(ApiResponse.error(ErrorCode.UNAUTHENTICATED));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
    // Do not log messages or stack traces containing records or credentials.
    log.error("Unhandled request failure: type={}", exception.getClass().getName());
    return ResponseEntity.internalServerError().body(ApiResponse.error(ErrorCode.INTERNAL_ERROR));
  }

  @ExceptionHandler(AiServiceException.class)
  ResponseEntity<ApiResponse<Void>> handleAiFailure(AiServiceException exception) {
    return ResponseEntity.status(ErrorCode.AI_SERVICE_UNAVAILABLE.status())
        .body(ApiResponse.error(ErrorCode.AI_SERVICE_UNAVAILABLE));
  }

  private static String reason(String message) {
    return message == null ? "입력 내용을 확인해주세요." : message;
  }

  private static String parameterName(MethodParameter parameter) {
    var query = parameter.getParameterAnnotation(RequestParam.class);
    if (query != null) {
      if (!query.name().isBlank()) return query.name();
      if (!query.value().isBlank()) return query.value();
    }
    var path = parameter.getParameterAnnotation(PathVariable.class);
    if (path != null) {
      if (!path.name().isBlank()) return path.name();
      if (!path.value().isBlank()) return path.value();
    }
    return parameter.getParameterName();
  }
}
