package com.hanmaum.backend.careitem.service;

import com.hanmaum.backend.careitem.dto.CreateCareItemRequest;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiFieldError;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class CareItemInput {
  private CareItemInput() {}

  public static void validate(CreateCareItemRequest request) {
    if (request == null) throw invalid("body", "등록 내용을 입력해주세요.");
    if (!"SCHEDULE".equals(request.itemType()))
      throw invalid("itemType", "이번 등록 API는 SCHEDULE을 지원합니다.");
    if (request.title() == null
        || request
            .title()
            .codePoints()
            .allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)))
      throw invalid("title", "제목을 입력해주세요.");
    text(request.title(), "title");
    text(request.description(), "description");
    if (request.fields() == null) throw invalid("fields", "일정 필드를 입력해주세요.");
    var fields = request.fields();
    text(fields.location(), "fields.location");
    try {
      if (fields.scheduledDate() != null) {
        if (!fields.scheduledDate().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
          throw new IllegalArgumentException();
        LocalDate.parse(fields.scheduledDate());
      }
    } catch (IllegalArgumentException | DateTimeException exception) {
      throw invalid("fields.scheduledDate", "YYYY-MM-DD 형식의 유효한 날짜를 입력해주세요.");
    }
    try {
      if (fields.scheduledTime() != null) {
        if (!fields.scheduledTime().matches("[0-9]{2}:[0-9]{2}"))
          throw new IllegalArgumentException();
        LocalTime.parse(fields.scheduledTime());
      }
    } catch (IllegalArgumentException | DateTimeException exception) {
      throw invalid("fields.scheduledTime", "HH:mm 형식의 유효한 시각을 입력해주세요.");
    }
    if (request.clientRequestId() == null) throw invalid("clientRequestId", "요청 ID를 입력해주세요.");
  }

  private static void text(String value, String field) {
    if (value != null
        && (value.indexOf('\0') >= 0
            || value.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)))
      throw invalid(field, "문자 형식을 확인해주세요.");
  }

  public static ApiException invalid(String field, String reason) {
    return new ApiException(
        CommonErrorCode.INVALID_REQUEST, List.of(new ApiFieldError(field, reason)));
  }
}
