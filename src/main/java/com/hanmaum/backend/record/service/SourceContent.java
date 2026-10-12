package com.hanmaum.backend.record.service;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiFieldError;
import java.util.List;

final class SourceContent {
  private SourceContent() {}

  static String validate(String value) {
    if (value == null
        || value
            .codePoints()
            .allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
      throw invalid("소식 내용을 입력해주세요.");
    }
    if (value.length() > 10000) throw invalid("소식은 10,000 UTF-16 코드 단위 이하로 입력해주세요.");
    if (value.indexOf('\0') >= 0 || value.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)) {
      throw invalid("소식의 문자 형식을 확인해주세요.");
    }
    return value;
  }

  private static ApiException invalid(String reason) {
    return new ApiException(
        CommonErrorCode.INVALID_REQUEST, List.of(new ApiFieldError("content", reason)));
  }
}
