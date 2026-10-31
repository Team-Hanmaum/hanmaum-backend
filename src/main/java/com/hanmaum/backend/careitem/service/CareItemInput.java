package com.hanmaum.backend.careitem.service;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiFieldError;
import java.util.List;

public final class CareItemInput {
  private CareItemInput() {}

  public static ApiException invalid(String field, String reason) {
    return new ApiException(
        CommonErrorCode.INVALID_REQUEST, List.of(new ApiFieldError(field, reason)));
  }
}
