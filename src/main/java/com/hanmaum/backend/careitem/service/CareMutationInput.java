package com.hanmaum.backend.careitem.service;

import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Strict input helpers shared by item leases and owner decisions; no scalar coercion. */
public final class CareMutationInput {
  private CareMutationInput() {}

  public static void object(JsonNode node, String path, Set<String> allowed) {
    if (node == null || !node.isObject()) throw CareItemInput.invalid(path, "객체를 입력해주세요.");
    for (String field : node.propertyNames())
      if (!allowed.contains(field))
        throw CareItemInput.invalid(path + "." + field, "허용하지 않는 필드입니다.");
  }

  public static String text(JsonNode node, String path, boolean required) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      if (required) throw CareItemInput.invalid(path, "값을 입력해주세요.");
      return null;
    }
    if (!node.isString()) throw CareItemInput.invalid(path, "문자열을 입력해주세요.");
    String value = node.asString();
    CareMutationSizeLimits.text(value, path);
    if (value.indexOf('\0') >= 0 || value.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF))
      throw CareItemInput.invalid(path, "문자 형식을 확인해주세요.");
    if (required
        && value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)))
      throw CareItemInput.invalid(path, "비어 있지 않은 값을 입력해주세요.");
    return value;
  }

  public static UUID uuid(JsonNode node, String path, boolean required) {
    String value = text(node, path, required);
    if (value == null) return null;
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException e) {
      throw CareItemInput.invalid(path, "UUID를 입력해주세요.");
    }
  }

  public static Long version(JsonNode node, String path, boolean required) {
    String value = text(node, path, required);
    if (value == null) return null;
    try {
      if (!value.matches("[1-9][0-9]*")) throw new IllegalArgumentException();
      return Long.valueOf(value);
    } catch (IllegalArgumentException e) {
      throw CareItemInput.invalid(path, "1 이상 정수 문자열을 입력해주세요.");
    }
  }
}
