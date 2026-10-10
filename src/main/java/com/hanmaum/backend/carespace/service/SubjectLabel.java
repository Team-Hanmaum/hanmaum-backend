package com.hanmaum.backend.carespace.service;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiFieldError;
import java.util.List;
import java.util.regex.Pattern;

final class SubjectLabel {
  private static final Pattern LINE_BREAK = Pattern.compile("\\R");

  private SubjectLabel() {}

  static String normalize(String value) {
    if (value == null) throw invalid("돌봄 대상 호칭을 입력해주세요.");
    // Reject line breaks before trimming so a pasted multiline value is never accepted silently.
    if (LINE_BREAK.matcher(value).find()) throw invalid("호칭은 한 줄로 입력해주세요.");
    int start = 0;
    int end = value.length();
    while (start < end && isSpace(value.codePointAt(start))) {
      start += Character.charCount(value.codePointAt(start));
    }
    while (start < end && isSpace(value.codePointBefore(end))) {
      end -= Character.charCount(value.codePointBefore(end));
    }
    String normalized = value.substring(start, end);
    if (normalized.isEmpty()) throw invalid("돌봄 대상 호칭을 입력해주세요.");
    if (normalized.codePointCount(0, normalized.length()) > 30) {
      throw invalid("호칭은 30자 이하로 입력해주세요.");
    }
    if (normalized.indexOf('\0') >= 0 || hasUnpairedSurrogate(normalized)) {
      throw invalid("호칭의 문자 형식을 확인해주세요.");
    }
    return normalized;
  }

  private static boolean isSpace(int value) {
    return Character.isWhitespace(value) || Character.isSpaceChar(value);
  }

  private static boolean hasUnpairedSurrogate(String value) {
    return value.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF);
  }

  private static ApiException invalid(String reason) {
    return new ApiException(
        CommonErrorCode.INVALID_REQUEST, List.of(new ApiFieldError("subjectLabel", reason)));
  }
}
