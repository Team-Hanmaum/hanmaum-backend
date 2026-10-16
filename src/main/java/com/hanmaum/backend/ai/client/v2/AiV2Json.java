package com.hanmaum.backend.ai.client.v2;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Isolated strict parser. Never changes the application's or v1's ObjectMapper. */
final class AiV2Json {
  static final JsonMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private AiV2Json() {}

  static JsonNode read(byte[] raw, int maxBytes) {
    try {
      AiV2PayloadLimits.check(raw.length, maxBytes);
      // Reject malformed UTF-8 and UTF-16/32 before Jackson's encoding autodetection.
      return MAPPER.readTree(
          StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString());
    } catch (Exception exception) {
      throw invalid();
    }
  }

  static byte[] write(Object value, int maxBytes) {
    var output = new AiV2PayloadLimits.LimitedBytes(maxBytes);
    MAPPER.writeValue(output, value);
    return output.toByteArray();
  }

  static void require(boolean condition) {
    if (!condition) throw invalid();
  }

  static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid AI v2 contract.");
  }

  static void keys(JsonNode node, String... keys) {
    keys(node, Set.of(keys));
  }

  static void keys(JsonNode node, Set<String> keys) {
    require(node != null && node.isObject() && node.propertyNames().equals(keys));
  }

  static String string(JsonNode node) {
    require(node != null && node.isString());
    return node.asText();
  }

  static String nonBlank(JsonNode node) {
    String value = string(node);
    require(
        value
            .codePoints()
            .anyMatch(c -> c != 0x85 && !Character.isWhitespace(c) && !Character.isSpaceChar(c)));
    return value;
  }

  static UUID uuid(JsonNode node) {
    String value = string(node);
    require(
        value.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
    return UUID.fromString(value);
  }

  static BigInteger integer(JsonNode node) {
    require(node != null && node.isIntegralNumber());
    return node.bigIntegerValue();
  }

  static <T> T nullable(JsonNode node, Function<JsonNode, T> read) {
    require(node != null); // Missing and explicit null are different.
    return node.isNull() ? null : read.apply(node);
  }

  static <E extends Enum<E>> E enumeration(JsonNode node, Class<E> type) {
    return Enum.valueOf(type, string(node));
  }

  static <T> List<T> list(JsonNode node, Function<JsonNode, T> read) {
    require(node != null && node.isArray());
    List<T> values = new ArrayList<>();
    for (JsonNode entry : node) values.add(read.apply(entry));
    return List.copyOf(values);
  }

  static <T> List<T> unique(List<T> values) {
    require(new HashSet<>(values).size() == values.size());
    return values;
  }
}
