package com.hanmaum.backend.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatusCode;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "공개 업무 API 공통 응답. CSRF·리다이렉트·204·내부 AI 응답은 제외")
public record ApiResponse<T>(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean success,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "SUCCESS") String code,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) T data,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ApiFieldError> errors,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "응답 생성 시각, UTC ISO 8601")
        Instant timestamp) {
  public ApiResponse {
    Objects.requireNonNull(code);
    Objects.requireNonNull(message);
    errors = List.copyOf(errors);
    Objects.requireNonNull(timestamp);
  }

  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>(true, "SUCCESS", "요청이 완료되었습니다.", data, List.of(), Instant.now());
  }

  public static <T> ApiResponse<T> accepted(T data) {
    return new ApiResponse<>(true, "ACCEPTED", "요청이 접수되었습니다.", data, List.of(), Instant.now());
  }

  public static ApiResponse<Void> error(ErrorCode code) {
    return error(code, List.of());
  }

  public static ApiResponse<Void> error(ErrorCode code, List<ApiFieldError> errors) {
    return new ApiResponse<>(false, code.name(), code.message(), null, errors, Instant.now());
  }

  public static ApiResponse<Void> error(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> error(ErrorCode.INVALID_REQUEST);
      case 401 -> error(ErrorCode.UNAUTHENTICATED);
      case 403 -> error(ErrorCode.FORBIDDEN);
      case 404 -> error(ErrorCode.RESOURCE_NOT_FOUND);
      case 500 -> error(ErrorCode.INTERNAL_ERROR);
      case 502 -> error(ErrorCode.AI_SERVICE_UNAVAILABLE);
      default ->
          new ApiResponse<>(
              false, "HTTP_" + status.value(), "요청을 처리할 수 없습니다.", null, List.of(), Instant.now());
    };
  }
}
