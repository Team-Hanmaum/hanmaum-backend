package com.hanmaum.backend.global.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hanmaum.backend.global.code.ErrorCode;
import io.swagger.v3.oas.models.Components;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorResponseDocumentationTests {
  @Test
  void rejectsDuplicatePublicCodesInsteadOfOverwritingAnErrorResponse() {
    var components = new Components();
    var codes =
        List.of(
            new TestCode(HttpStatus.BAD_REQUEST, "DUPLICATE_CODE", "첫 번째 오류"),
            new TestCode(HttpStatus.CONFLICT, "DUPLICATE_CODE", "두 번째 오류"));

    assertThatThrownBy(() -> ErrorResponseDocumentation.register(components, codes))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DUPLICATE_CODE");
    assertThat(components.getResponses()).isNull();
  }

  private record TestCode(HttpStatus status, String code, String message) implements ErrorCode {}
}
