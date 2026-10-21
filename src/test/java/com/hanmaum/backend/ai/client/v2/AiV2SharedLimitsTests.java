package com.hanmaum.backend.ai.client.v2;

import static com.hanmaum.backend.ai.client.v2.V2Fixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.hanmaum.backend.ai.code.AiFailureCode;
import com.hanmaum.backend.ai.fixture.V2GeneratedPayloads;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

class AiV2SharedLimitsTests {
  private final AiV2Contract contract = new AiV2Contract(LIMITS);
  private static final Set<String> FREE_TEXT =
      Set.of("title", "description", "location", "observedTimeText", "sourceName", "answerText");

  @TestFactory
  List<DynamicTest> reproducesAll116PinnedPayloadsInJava() {
    var tests = new ArrayList<DynamicTest>();
    V2GeneratedPayloads.catalog()
        .path("payloads")
        .properties()
        .forEach(
            entry ->
                tests.add(
                    DynamicTest.dynamicTest(
                        entry.getKey() + " bytes and measurements",
                        () -> {
                          byte[] raw = V2GeneratedPayloads.raw(entry.getKey());
                          JsonNode expected = entry.getValue().path("expected");
                          JsonNode tree = JSON.readTree(raw);
                          assertThat(raw.length).isEqualTo(expected.path("byteLength").asInt());
                          assertThat(
                                  HexFormat.of()
                                      .formatHex(MessageDigest.getInstance("SHA-256").digest(raw)))
                              .isEqualTo(expected.path("sha256").asText());
                          assertThat(V2GeneratedPayloads.stringUnits(tree))
                              .isEqualTo(expected.path("stringUnits").asLong());
                          assertThat(AiV2SizeRules.stringUnits(tree, Long.MAX_VALUE))
                              .isEqualTo(expected.path("stringUnits").asLong());
                          var measured =
                              measurements(
                                  tree, raw.length, entry.getValue().path("side").asText());
                          expected
                              .path("measurements")
                              .properties()
                              .forEach(
                                  metric ->
                                      assertThat(measured.get(metric.getKey()))
                                          .as(metric.getKey())
                                          .isEqualTo(metric.getValue().asLong()));
                        })));
    assertThat(tests).hasSize(116);
    return tests;
  }

  @TestFactory
  List<DynamicTest> consumesAll102LimitCasesWithoutAiOrDb() throws Exception {
    var tests = new ArrayList<DynamicTest>();
    for (JsonNode scenario : metadata("cases/limits.json")) {
      tests.add(
          DynamicTest.dynamicTest(
              scenario.path("caseId").asText(),
              () -> {
                if (scenario.path("measurementOnly").asBoolean()) {
                  String recipe = scenario.path("payloadRecipe").asText();
                  JsonNode tree = JSON.readTree(V2GeneratedPayloads.raw(recipe));
                  assertThat(AiV2SizeRules.stringUnits(tree, Long.MAX_VALUE))
                      .isEqualTo(
                          V2GeneratedPayloads.catalog()
                              .path("payloads")
                              .path(recipe)
                              .path("expected")
                              .path("stringUnits")
                              .asLong());
                  return;
                }
                byte[] request =
                    V2GeneratedPayloads.raw(scenario.path("request").path("bodyRecipe").asText());
                boolean requestSide =
                    scenario.path("boundary").path("metric").asText().startsWith("request.");
                byte[] measured =
                    requestSide
                        ? request
                        : V2GeneratedPayloads.raw(
                            scenario.path("provider").path("bodyRecipe").asText());
                JsonNode tree = JSON.readTree(measured);
                // Check size rules independently, even when referential validation also must fail.
                Throwable sizeFailure =
                    catchThrowable(
                        () -> {
                          AiV2PayloadLimits.check(
                              measured.length,
                              requestSide ? LIMITS.requestBytes() : LIMITS.responseBytes());
                          if (requestSide) AiV2SizeRules.request(tree);
                          else AiV2SizeRules.response(tree);
                        });
                if (scenario.path("expectedLimitViolations").isEmpty())
                  assertThat(sizeFailure).isNull();
                else assertThat(sizeFailure).isInstanceOf(IllegalArgumentException.class);
                int expectedStatus =
                    scenario.path("expected").path("ai").path("httpStatus").asInt();
                if (requestSide) {
                  if (expectedStatus != 200)
                    assertFailure(
                        catchThrowable(() -> contract.parseRequest(request)),
                        AiFailureCode.AI_INTEGRATION_ERROR);
                  else assertThat(contract.parseRequest(request)).isNotNull();
                } else {
                  var parsed = contract.parseRequest(request);
                  if (expectedStatus != 200)
                    assertFailure(
                        catchThrowable(() -> contract.parseResponse(parsed, measured)),
                        AiFailureCode.AI_INVALID_RESPONSE);
                  else assertThat(contract.parseResponse(parsed, measured)).isNotNull();
                }
              }));
    }
    assertThat(tests).hasSize(102);
    return tests;
  }

  @Test
  void rechecksTheActualSerializedRequestAfterUuidNormalization() {
    // Decoding compact IDs at 100,000 units is valid; the larger outgoing spelling is not.
    var request = contract.parseRequest(V2GeneratedPayloads.raw("request-pre-normalization-at"));
    assertFailure(
        catchThrowable(() -> contract.encodeRequest(request)), AiFailureCode.AI_INTEGRATION_ERROR);
  }

  @Test
  void configuredBudgetsCannotBypassTheAgreedWireCaps() {
    assertThat(new AiV2PayloadLimits(Integer.MAX_VALUE, Integer.MAX_VALUE)).isEqualTo(LIMITS);
    assertThat(new AiV2PayloadLimits(100, 200)).isEqualTo(new AiV2PayloadLimits(100, 200));
  }

  private static Map<String, Long> measurements(JsonNode root, int bytes, String side) {
    var metrics = new HashMap<String, Long>();
    metrics.put(side + ".bytes", (long) bytes);
    metrics.put(side + ".stringUnits", V2GeneratedPayloads.stringUnits(root));
    if (side.equals("request")) {
      metrics.put("request.content", (long) root.path("content").asText().length());
      metrics.put("request.candidates", (long) root.path("candidates").size());
      long total = 0;
      for (JsonNode candidate : root.path("candidates")) {
        total += candidate.path("basis").size();
        max(metrics, "request.basisPerCandidate", candidate.path("basis").size());
        for (JsonNode basis : candidate.path("basis"))
          max(metrics, "request.basisText", basis.path("text").asText().length());
        for (String field : FREE_TEXT)
          max(metrics, "request.currentText", length(candidate.path("currentValues").path(field)));
      }
      metrics.put("request.basisTotal", total);
      for (String key : List.of("basisPerCandidate", "basisText", "currentText"))
        metrics.putIfAbsent("request." + key, 0L);
    } else {
      metrics.put("response.suggestions", (long) root.path("suggestions").size());
      for (JsonNode suggestion : root.path("suggestions")) {
        max(metrics, "response.title", length(suggestion.path("title")));
        max(metrics, "response.evidence", length(suggestion.path("evidence")));
        max(metrics, "response.candidateItemIds", suggestion.path("candidateItemIds").size());
        max(metrics, "response.basisReferences", suggestion.path("basisReferences").size());
        for (String field : FREE_TEXT)
          max(metrics, "response.changeText", length(suggestion.path("changes").path(field)));
        long total = 0;
        for (JsonNode ref : suggestion.path("basisReferences")) {
          total += ref.path("basisIds").size();
          max(metrics, "response.basisIdsPerItem", ref.path("basisIds").size());
        }
        max(metrics, "response.basisIdsPerSuggestion", total);
      }
      for (String key :
          List.of(
              "title",
              "evidence",
              "changeText",
              "candidateItemIds",
              "basisReferences",
              "basisIdsPerItem",
              "basisIdsPerSuggestion")) metrics.putIfAbsent("response." + key, 0L);
    }
    return metrics;
  }

  private static long length(JsonNode node) {
    return node.isString() ? node.asText().length() : 0;
  }

  private static void max(Map<String, Long> metrics, String key, long value) {
    metrics.merge(key, value, Math::max);
  }
}
