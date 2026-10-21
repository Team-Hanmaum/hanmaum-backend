package com.hanmaum.backend.ai.fixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Test-only portable recipe interpreter. Never reads or derives expectations from BE code. */
public final class V2GeneratedPayloads {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNode CATALOG = read("generated.json");

  private V2GeneratedPayloads() {}

  public static JsonNode read(String path) {
    try {
      return JSON.readTree(new ClassPathResource("ai-contract/v2/" + path).getContentAsByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("Missing shared fixture " + path, exception);
    }
  }

  public static JsonNode catalog() {
    return CATALOG;
  }

  public static byte[] raw(String name) {
    return materialize(name, new HashSet<>());
  }

  private static byte[] materialize(String name, Set<String> ancestors) {
    if (!ancestors.add(name)) throw new IllegalArgumentException("Cyclic recipe " + name);
    try {
      JsonNode recipe = CATALOG.path("recipes").required(name);
      JsonNode data =
          recipe.has("baseRecipe")
              ? JSON.readTree(materialize(recipe.path("baseRecipe").asText(), ancestors))
              : read(recipe.path("baseFile").asText()).deepCopy();
      for (JsonNode operation : recipe.path("operations")) {
        String kind = operation.path("op").asText();
        if (kind.equals("fillStringTotal")) {
          long remaining = operation.path("units").asLong() - stringUnits(data);
          if (remaining < 0) throw new IllegalArgumentException("Negative fill");
          for (JsonNode pointer : operation.path("paths")) {
            String previous = data.at(pointer.asText()).asText();
            int count =
                (int)
                    Math.min(
                        remaining, operation.path("fieldMaxUnits").asInt() - previous.length());
            if (count < 0) throw new IllegalArgumentException("Negative capacity");
            set(data, pointer.asText(), JSON.valueToTree(previous + "a".repeat(count)));
            remaining -= count;
          }
          if (remaining != 0) throw new IllegalArgumentException("Insufficient fill capacity");
        } else {
          String path = operation.path("path").asText();
          JsonNode value =
              switch (kind) {
                case "set" -> operation.required("value").deepCopy();
                case "repeatText" ->
                    JSON.valueToTree(
                        operation.path("text").asText().repeat(operation.path("count").asInt()));
                case "appendText" ->
                    JSON.valueToTree(
                        data.at(path).asText()
                            + operation
                                .path("text")
                                .asText()
                                .repeat(operation.path("count").asInt()));
                default ->
                    throw new IllegalArgumentException("Unsupported recipe operation " + kind);
              };
          set(data, path, value);
        }
      }
      StringBuilder json = new StringBuilder();
      append(data, recipe.path("encoding").path("ensureAscii").asBoolean(true), json);
      byte[] raw = json.toString().getBytes(StandardCharsets.UTF_8);
      int target = recipe.path("encoding").path("padToBytes").asInt(raw.length);
      if (target < raw.length) throw new IllegalArgumentException("Invalid padding");
      byte[] result = Arrays.copyOf(raw, target);
      Arrays.fill(result, raw.length, target, (byte) ' ');
      return result;
    } finally {
      ancestors.remove(name);
    }
  }

  private static void set(JsonNode root, String pointer, JsonNode value) {
    int separator = pointer.lastIndexOf('/');
    if (separator < 0) throw new IllegalArgumentException("Expected JSON pointer");
    JsonNode parent = root.at(pointer.substring(0, separator));
    String key = pointer.substring(separator + 1).replace("~1", "/").replace("~0", "~");
    if (parent.isArray()) ((ArrayNode) parent).set(Integer.parseInt(key), value);
    else ((ObjectNode) parent).set(key, value);
  }

  public static long stringUnits(JsonNode node) {
    if (node.isString()) return node.asText().length();
    long total = 0;
    if (node.isObject() || node.isArray()) for (JsonNode child : node) total += stringUnits(child);
    return total;
  }

  private static void append(JsonNode node, boolean ascii, StringBuilder output) {
    if (node.isObject()) {
      output.append('{');
      var keys = new ArrayList<>(node.propertyNames());
      keys.sort(String::compareTo);
      for (int i = 0; i < keys.size(); i++) {
        if (i > 0) output.append(',');
        quote(keys.get(i), ascii, output);
        output.append(':');
        append(node.get(keys.get(i)), ascii, output);
      }
      output.append('}');
    } else if (node.isArray()) {
      output.append('[');
      for (int i = 0; i < node.size(); i++) {
        if (i > 0) output.append(',');
        append(node.get(i), ascii, output);
      }
      output.append(']');
    } else if (node.isString()) quote(node.asText(), ascii, output);
    else output.append(node.toString());
  }

  private static void quote(String value, boolean ascii, StringBuilder output) {
    output.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> output.append("\\\"");
        case '\\' -> output.append("\\\\");
        case '\b' -> output.append("\\b");
        case '\f' -> output.append("\\f");
        case '\n' -> output.append("\\n");
        case '\r' -> output.append("\\r");
        case '\t' -> output.append("\\t");
        default -> {
          if (c < 32 || ascii && c > 126) {
            output.append("\\u");
            for (int shift = 12; shift >= 0; shift -= 4)
              output.append(Character.forDigit((c >> shift) & 15, 16));
          } else output.append(c);
        }
      }
    }
    output.append('"');
  }
}
