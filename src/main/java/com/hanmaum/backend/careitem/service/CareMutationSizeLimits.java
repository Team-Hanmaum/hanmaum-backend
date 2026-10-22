package com.hanmaum.backend.careitem.service;

import java.io.OutputStream;
import java.util.ArrayDeque;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Agreed owner-decision limits. Sizes use compact UTF-8 JSON, not JSONB disk layout. */
public final class CareMutationSizeLimits {
  public static final int REQUEST_BYTES = 1_048_576;
  public static final int TEXT_UNITS = 10_000;
  public static final int DOCUMENT_BYTES = 524_288;

  private CareMutationSizeLimits() {}

  public static void text(String value, String path) {
    if (value.length() > TEXT_UNITS)
      throw CareItemInput.invalid(path, "문자열은 10,000 UTF-16 단위 이하로 입력해주세요.");
  }

  public static void strings(JsonNode root, String path) {
    if (root == null) return;
    var pending = new ArrayDeque<JsonNode>();
    pending.push(root);
    while (!pending.isEmpty()) {
      var node = pending.pop();
      if (node.isString()) text(node.asString(), path);
      else if (node.isObject() || node.isArray()) node.forEach(pending::push);
    }
  }

  /** Count and stop without building a second serialized string/byte array. */
  public static int bytes(ObjectMapper mapper, JsonNode value, int limit, String path) {
    var output = new SizeCounter(limit);
    try {
      mapper.writeValue(output, value);
    } catch (RuntimeException exception) {
      if (output.exceeded) throw CareItemInput.invalid(path, "허용된 JSON 크기를 초과했습니다.");
      throw exception;
    }
    return output.count;
  }

  private static final class SizeCounter extends OutputStream {
    private final int limit;
    private int count;
    private boolean exceeded;

    SizeCounter(int limit) {
      this.limit = limit;
    }

    @Override
    public void write(int value) {
      add(1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      add(length);
    }

    private void add(int length) {
      if ((long) count + length > limit) {
        exceeded = true;
        throw new SizeExceeded();
      }
      count += length;
    }
  }

  private static final class SizeExceeded extends RuntimeException {}
}
