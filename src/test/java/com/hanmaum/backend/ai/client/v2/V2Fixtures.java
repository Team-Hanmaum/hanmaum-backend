package com.hanmaum.backend.ai.client.v2;

import static org.assertj.core.api.Assertions.assertThat;

import com.hanmaum.backend.ai.code.AiFailureCode;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class V2Fixtures {
  static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .build();
  static final String BASE = "payloads/requests/base.json";
  private static final Set<String> UUID_FIELDS =
      Set.of(
          "requestId",
          "careSpaceId",
          "recordId",
          "itemId",
          "basisId",
          "targetItemId",
          "candidateItemIds",
          "basisIds",
          "assigneeMembershipId");

  private V2Fixtures() {}

  static byte[] raw(String path) throws IOException {
    return new ClassPathResource("ai-contract/v2/" + path).getContentAsByteArray();
  }

  static JsonNode metadata(String path) throws IOException {
    return JSON.readTree(raw(path));
  }

  static void assertFailure(Throwable exception, AiFailureCode expected) {
    assertThat(exception).isInstanceOf(AiV2Exception.class).hasNoCause();
    assertThat(((AiV2Exception) exception).failureCode()).isEqualTo(expected);
    assertThat(exception).hasMessage(expected.message());
  }

  static JsonNode uuidValues(JsonNode input) {
    return uuidValues(input, "");
  }

  private static JsonNode uuidValues(JsonNode input, String field) {
    if (input.isObject()) {
      ObjectNode result = JSON.createObjectNode();
      input.properties().forEach(e -> result.set(e.getKey(), uuidValues(e.getValue(), e.getKey())));
      return result;
    }
    if (input.isArray()) {
      ArrayNode result = JSON.createArrayNode();
      input.forEach(n -> result.add(uuidValues(n, field)));
      return result;
    }
    if (input.isString() && UUID_FIELDS.contains(field)) {
      return JSON.valueToTree(UUID.fromString(input.asText()).toString());
    }
    return input;
  }
}
