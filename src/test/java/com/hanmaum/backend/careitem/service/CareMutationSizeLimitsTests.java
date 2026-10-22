package com.hanmaum.backend.careitem.service;

import static org.assertj.core.api.Assertions.*;

import com.hanmaum.backend.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CareMutationSizeLimitsTests {
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void countsSerializedUtf8IncludingEscapesAndHonorsExactRemainingBudget() {
    var body = mapper.createObjectNode().put("text", "한😀\"\\\n");
    int bytes = mapper.writeValueAsBytes(body).length;
    assertThat(CareMutationSizeLimits.bytes(mapper, body, bytes, "values")).isEqualTo(bytes);
    assertThatThrownBy(() -> CareMutationSizeLimits.bytes(mapper, body, bytes - 1, "values"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code().code()).isEqualTo("INVALID_REQUEST"));
  }

  @Test
  void documentLimitIncludesUnchangedStoredText() {
    var body =
        mapper.createObjectNode().put("title", "새 제목").put("description", "가".repeat(10_001));
    assertThatThrownBy(() -> CareMutationSizeLimits.strings(body, "values"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code().code()).isEqualTo("INVALID_REQUEST"));
  }
}
