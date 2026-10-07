package com.hanmaum.backend.global.exception;

import com.hanmaum.backend.global.response.ApiFieldError;
import com.hanmaum.backend.global.response.ErrorCode;
import java.util.List;

/** A known failure with a public error code and safe request-field descriptions. */
public class ApiException extends RuntimeException {
  private final ErrorCode code;
  private final List<ApiFieldError> errors;

  public ApiException(ErrorCode code) {
    this(code, List.of());
  }

  public ApiException(ErrorCode code, List<ApiFieldError> errors) {
    super(code.message());
    this.code = code;
    this.errors = List.copyOf(errors);
  }

  public ErrorCode code() {
    return code;
  }

  public List<ApiFieldError> errors() {
    return errors;
  }
}
