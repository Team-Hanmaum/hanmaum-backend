package com.hanmaum.backend.ai.client.v2;

import static com.hanmaum.backend.ai.client.v2.AiV2Json.require;

import java.util.ArrayDeque;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** v2-20261021.1 admission limits. Inspect decoded JSON before UUID/type normalization. */
final class AiV2SizeRules {
  static final Set<String> FREE_TEXT =
      Set.of("title", "description", "location", "observedTimeText", "sourceName", "answerText");

  private AiV2SizeRules() {}

  static void request(JsonNode root) {
    require(stringUnits(root, 100_000) <= 100_000);
    text(root.path("content"), 10_000);
    JsonNode candidates = root.path("candidates");
    count(candidates, 100);
    int totalBasis = 0;
    for (JsonNode candidate : candidates) {
      JsonNode basis = candidate.path("basis");
      count(basis, 20);
      totalBasis += basis.size();
      require(totalBasis <= 500);
      for (JsonNode entry : basis) text(entry.path("text"), 20_000);
      freeText(candidate.path("currentValues"));
    }
  }

  static void response(JsonNode root) {
    require(stringUnits(root, 50_000) <= 50_000);
    JsonNode suggestions = root.path("suggestions");
    count(suggestions, 30);
    for (JsonNode suggestion : suggestions) {
      text(suggestion.path("title"), 500);
      text(suggestion.path("evidence"), 10_000);
      freeText(suggestion.path("changes"));
      count(suggestion.path("candidateItemIds"), 100);
      JsonNode references = suggestion.path("basisReferences");
      count(references, 100);
      int totalIds = 0;
      for (JsonNode reference : references) {
        JsonNode ids = reference.path("basisIds");
        count(ids, 20);
        totalIds += ids.size();
        require(totalIds <= 500);
      }
    }
  }

  private static void freeText(JsonNode node) {
    for (String field : FREE_TEXT) text(node.path(field), 10_000);
  }

  // Shape/null/required-field rules remain in AiV2Contract; these are independent size guards.
  static void text(JsonNode node, int limit) {
    if (node.isString()) require(node.asText().length() <= limit);
  }

  private static void count(JsonNode node, int limit) {
    if (node.isArray()) require(node.size() <= limit);
  }

  static long stringUnits(JsonNode root, long stopAfter) {
    long units = 0;
    var pending = new ArrayDeque<JsonNode>();
    pending.push(root);
    while (!pending.isEmpty()) {
      JsonNode node = pending.pop();
      if (node.isString()) {
        units += node.asText().length();
        if (units > stopAfter) return units;
      } else if (node.isObject() || node.isArray()) node.forEach(pending::push);
    }
    return units;
  }
}
