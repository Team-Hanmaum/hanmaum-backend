package com.hanmaum.backend.global.code;

import org.springframework.http.HttpStatus;

/** Public error contract shared by common and domain-specific error enums. */
public interface ErrorCode {
  HttpStatus status();

  /** The stable, application-wide unique code exposed to API clients. */
  String code();

  String message();
}
