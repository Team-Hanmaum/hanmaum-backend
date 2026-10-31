package com.hanmaum.backend.global.command;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Object key order is not part of command identity; array order and explicit nulls are. */
public final class CommandJson {
  private CommandJson() {}

  public static JsonNode canonical(ObjectMapper mapper, JsonNode node) {
    if (node.isObject()) {
      var out = mapper.createObjectNode();
      node.propertyNames().stream()
          .sorted()
          .forEach(k -> out.set(k, canonical(mapper, node.get(k))));
      return out;
    }
    if (node.isArray()) {
      var out = mapper.createArrayNode();
      node.forEach(n -> out.add(canonical(mapper, n)));
      return out;
    }
    return node;
  }
}
