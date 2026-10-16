package com.hanmaum.backend.ai.client.v2;

import static com.hanmaum.backend.ai.client.v2.V2Fixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.hanmaum.backend.ai.code.AiFailureCode;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class AiV2ContractTests {
  // These are HTTP/auth/provider outcomes, not request/response model failures.
  static final Map<String, String> HTTP_CASES =
      Map.of(
          "A-auth-missing", "AI internal authentication belongs to AI",
          "A-auth-invalid", "AI internal authentication belongs to AI",
          "A-header-mismatch", "BE maps the AI error; always generates its own matching header",
          "A-header-uuid-case", "BE generates the matching header from UUID values",
          "A-unavailable", "Provider failure mapping",
          "A-disabled", "Provider configuration failure mapping",
          "A-timeout", "Provider timeout mapping");
  private final AiV2Contract contract = new AiV2Contract();

  @TestFactory
  List<DynamicTest> consumes139StaticContractCasesUsingOriginalPayloads() throws Exception {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode scenario : metadata("cases/static.json")) {
      String id = scenario.path("caseId").asText();
      if (HTTP_CASES.containsKey(id)) continue;
      tests.add(
          DynamicTest.dynamicTest(
              id,
              () -> {
                byte[] input = raw(scenario.path("request").path("bodyFile").asText());
                JsonNode expected = scenario.path("expected").path("ai");
                if (expected.path("code").asText().equals("INVALID_REQUEST")) {
                  assertFailure(
                      catchThrowable(() -> contract.parseRequest(input)),
                      AiFailureCode.AI_INTEGRATION_ERROR);
                  return;
                }
                var request = contract.parseRequest(input);
                assertThat(uuidValues(JSON.readTree(contract.encodeRequest(request))))
                    .isEqualTo(uuidValues(JSON.readTree(input)));
                byte[] provider = raw(scenario.path("provider").path("bodyFile").asText());
                if (expected.path("code").asText().equals("INVALID_AI_RESPONSE")) {
                  assertFailure(
                      catchThrowable(() -> contract.parseResponse(request, provider)),
                      AiFailureCode.AI_INVALID_RESPONSE);
                } else {
                  assertThat(expected.path("httpStatus").asInt()).isEqualTo(200);
                  var response = contract.parseResponse(request, provider);
                  assertThat(uuidValues(JSON.valueToTree(response)))
                      .isEqualTo(uuidValues(metadata(expected.path("bodyFile").asText())));
                }
              }));
    }
    assertThat(tests).hasSize(139);
    assertThat(metadata("cases/static.json").size()).isEqualTo(tests.size() + HTTP_CASES.size());
    return tests;
  }

  @TestFactory
  List<DynamicTest> rejectsMalformedWireRepresentationsWithoutLeakingThem() throws Exception {
    String base = new String(raw(BASE), StandardCharsets.UTF_8);
    List<String> invalid =
        List.of(
            "null",
            "[]",
            "{}",
            base + " {}",
            base + " true",
            base.replace("\"content\":", "\"content\":\"private-source\",\"content\":"),
            base.replace("\"version\": 3", "\"version\": true"),
            base.replace("\"version\": 3", "\"version\": 3.0"),
            base.replace("\"version\": 3", "\"version\": \"3\""),
            base.replace("2026-10-16T09:00:00.123456+09:00", "2026-02-30T09:00:00Z"),
            base.replace("2026-10-16T09:00:00.123456+09:00", "2026-10-16T24:00:00Z"),
            base.replace("2026-10-16T09:00:00.123456+09:00", "2026-10-16T09:00:00.1234560Z"),
            base.replace("2026-10-16T09:00:00.123456+09:00", "2026-10-16T09:00:00+24:00"),
            base.replace("2026-10-16T09:00:00.123456+09:00", "0000-10-16T09:00:00Z"),
            base.replace("2026-10-20", "2026-02-29"),
            base.replace("\"09:00\"", "\"24:00\""),
            base.replace("\"recordedAt\":", "\"recordedAt\":0,\"recordedAt\":"));
    var tests = new ArrayList<DynamicTest>();
    for (int i = 0; i < invalid.size(); i++) {
      byte[] raw = invalid.get(i).getBytes(StandardCharsets.UTF_8);
      tests.add(
          DynamicTest.dynamicTest(
              "invalid wire " + i,
              () ->
                  assertFailure(
                      catchThrowable(() -> contract.parseRequest(raw)),
                      AiFailureCode.AI_INTEGRATION_ERROR)));
    }
    return tests;
  }

  @Test
  void rejectsNonUtf8AndMissingNullableKeys() throws Exception {
    String base = new String(raw(BASE), StandardCharsets.UTF_8);
    for (byte[] input :
        List.of(base.getBytes(StandardCharsets.UTF_16), new byte[] {(byte) 0xc3, 0x28})) {
      assertFailure(
          catchThrowable(() -> contract.parseRequest(input)), AiFailureCode.AI_INTEGRATION_ERROR);
    }
    ObjectNode root = (ObjectNode) metadata(BASE);
    ((ObjectNode) root.path("candidates").get(2).path("currentValues")).remove("answerText");
    assertFailure(
        catchThrowable(() -> contract.parseRequest(JSON.writeValueAsBytes(root))),
        AiFailureCode.AI_INTEGRATION_ERROR);
  }

  @Test
  void preservesOpaqueTextTimestampAndAllowsV2PayloadsBeyondV1Limits() throws Exception {
    ObjectNode root = (ObjectNode) metadata(BASE);
    String opaque = "  { \"description\" : \"e\u0301\", \"title\": \"원본\" }\n";
    String text = "원문 ".repeat(3000) + "\n";
    root.put("content", text);
    ArrayNode candidates = (ArrayNode) root.path("candidates");
    ObjectNode first = (ObjectNode) candidates.get(0);
    first.put("version", new java.math.BigInteger("9223372036854775808"));
    ((ObjectNode) first.path("basis").get(0)).put("text", "근거".repeat(2001));
    ((ObjectNode) first.path("basis").get(1)).put("text", opaque);
    for (int i = 0; i < 51; i++) {
      ObjectNode copy = first.deepCopy();
      copy.put("itemId", new UUID(1, i).toString());
      candidates.add(copy);
    }
    var request = contract.parseRequest(JSON.writeValueAsBytes(root));
    assertThat(JSON.readTree(contract.encodeRequest(request))).isEqualTo(root);
    assertThat(request.content()).isEqualTo(text);
    assertThat(request.candidates().getFirst().basis().get(1).text()).isEqualTo(opaque);
    assertThat(request.recordedAt()).isEqualTo("2026-10-16T09:00:00.123456+09:00");
    ObjectNode output = (ObjectNode) metadata("payloads/responses/empty.json");
    ArrayNode suggestions = (ArrayNode) output.path("suggestions");
    for (int i = 0; i < 31; i++) {
      suggestions
          .addObject()
          .put("operation", "CREATE")
          .put("type", "QUESTION")
          .putNull("targetItemId")
          .putNull("expectedVersion")
          .set("candidateItemIds", JSON.createArrayNode());
      ObjectNode suggestion = (ObjectNode) suggestions.get(i);
      suggestion.put("title", "제목".repeat(201)).put("evidence", "원문").put("relationKind", "NEW");
      suggestion.set("changes", JSON.createObjectNode().put("description", "설명".repeat(2001)));
      for (String field : List.of("clearFields", "reviewReasons", "basisReferences"))
        suggestion.putArray(field);
    }
    assertThat(contract.parseResponse(request, JSON.writeValueAsBytes(output)).suggestions())
        .hasSize(31);
  }

  @Test
  void treatsUnicodeWhitespaceAsBlankWithoutTrimmingNonBlankContent() throws Exception {
    for (String blank : List.of("\u0085", "\u00a0", "\u2007", "\u202f", "\u3000", " \t\n")) {
      ObjectNode root = (ObjectNode) metadata(BASE);
      root.put("content", blank);
      assertFailure(
          catchThrowable(() -> contract.parseRequest(JSON.writeValueAsBytes(root))),
          AiFailureCode.AI_INTEGRATION_ERROR);
      root.put("content", blank + "원문" + blank);
      var request = contract.parseRequest(JSON.writeValueAsBytes(root));
      assertThat(request.content()).isEqualTo(blank + "원문" + blank);
    }
  }

  @Test
  void validatesConstructedRequestsAndRedactsDiagnosticStrings() throws Exception {
    var request = contract.parseRequest(raw(BASE));
    var invalid =
        new AiV2AnalysisRequest(
            null,
            request.careSpaceId(),
            request.recordId(),
            "private-source",
            request.recordedAt(),
            request.timezone(),
            request.candidates());
    assertFailure(
        catchThrowable(() -> contract.encodeRequest(invalid)), AiFailureCode.AI_INTEGRATION_ERROR);
    assertThat(request.toString()).doesNotContain(request.content());
    var candidate = request.candidates().getFirst();
    assertThat(candidate.toString()).doesNotContain("정기 방문");
    assertThat(candidate.currentValues().toString()).doesNotContain("정기 방문");
    assertThat(candidate.basis().getFirst().toString()).doesNotContain("기존 기록");
    var response = contract.parseResponse(request, raw("payloads/responses/update.json"));
    assertThat(response.toString()).doesNotContain(response.suggestions().getFirst().title());
    assertThat(response.suggestions().getFirst().toString())
        .doesNotContain(response.suggestions().getFirst().evidence());
    assertThatThrownBy(() -> response.suggestions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
