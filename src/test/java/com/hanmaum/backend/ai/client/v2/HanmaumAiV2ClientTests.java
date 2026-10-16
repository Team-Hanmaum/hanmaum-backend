package com.hanmaum.backend.ai.client.v2;

import static com.hanmaum.backend.ai.client.v2.V2Fixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.hanmaum.backend.ai.code.AiFailureCode;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisRequest;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

class HanmaumAiV2ClientTests {
  private final AiV2Contract contract = new AiV2Contract(LIMITS);

  @TestFactory
  List<DynamicTest> consumesStaticHttpOutcomesWithoutCallingAiOrAProvider() throws Exception {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode scenario : metadata("cases/static.json")) {
      tests.add(
          DynamicTest.dynamicTest(
              scenario.path("caseId").asText() + " HTTP adapter",
              () -> {
                JsonNode expected = scenario.path("expected").path("ai");
                int status = expected.path("httpStatus").asInt();
                // Malformed request fixtures are tested before serialization in AiV2ContractTests.
                // Here a valid local request receives a simulated AI error, not a real AI 422.
                var request =
                    contract.parseRequest(
                        raw(
                            status == 422
                                ? BASE
                                : scenario.path("request").path("bodyFile").asText()));
                byte[] body =
                    expected.has("bodyFile")
                        ? raw(expected.path("bodyFile").asText())
                        : errorBody(expected.path("code").asText());
                AiFailureCode failure =
                    status == 200
                        ? null
                        : AiFailureCode.valueOf(
                            scenario.path("expected").path("be").path("failureCode").asText());
                exchange(request, status, body, failure);
              }));
    }
    assertThat(tests).hasSize(146);
    return tests;
  }

  @TestFactory
  List<DynamicTest> independentlyRejectsAll69InvalidProviderResultsEvenWithHttp200()
      throws Exception {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode scenario : metadata("cases/static.json")) {
      if (!scenario.path("expected").path("ai").path("code").asText().equals("INVALID_AI_RESPONSE"))
        continue;
      tests.add(
          DynamicTest.dynamicTest(
              scenario.path("caseId").asText() + " invalid 200",
              () ->
                  exchange(
                      contract.parseRequest(
                          raw(scenario.path("request").path("bodyFile").asText())),
                      200,
                      raw(scenario.path("provider").path("bodyFile").asText()),
                      AiFailureCode.AI_INVALID_RESPONSE)));
    }
    assertThat(tests).hasSize(69);
    return tests;
  }

  @TestFactory
  List<DynamicTest> consumesReceiptCasesAsIndependentResponsesNotAsReceiptStoreTests()
      throws Exception {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode scenario : metadata("cases/receipts.json")) {
      if (scenario.path("caseId").asText().equals("B-uncomparable-record")) {
        // Contract specifies BLOCKED/providerCalls=0, but deliberately no HTTP status/code.
        // Do not invent a wire outcome or count this as a passing receipt implementation.
        assertThat(scenario.path("steps").get(1).path("expected").path("ai").has("httpStatus"))
            .isFalse();
        continue;
      }
      tests.add(
          DynamicTest.dynamicTest(
              scenario.path("caseId").asText() + " response mapping only",
              () -> {
                int checked = 0;
                for (JsonNode step : scenario.path("steps")) {
                  if (step.path("action").asText().equals("SUBMIT")) {
                    JsonNode expected = step.path("expected").path("ai");
                    checkReceiptResponse(expected);
                    checked++;
                  } else if (step.path("action").asText().equals("SUBMIT_CONCURRENT")) {
                    // Test each returned HTTP result. Concurrency/atomic registration remain
                    // AI-owned.
                    for (JsonNode expected : step.path("expected").path("aiResults")) {
                      checkReceiptResponse(expected);
                      checked++;
                    }
                  }
                }
                assertThat(checked).isPositive();
              }));
    }
    assertThat(tests).hasSize(29);
    return tests;
  }

  @TestFactory
  List<DynamicTest> validatesOnlyTheWirePortionOfBackendCasesWithoutClaimingDbExecution()
      throws Exception {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode scenario : metadata("cases/backend.json")) {
      tests.add(
          DynamicTest.dynamicTest(
              scenario.path("caseId").asText() + " wire portion only",
              () -> {
                JsonNode response = scenario.path("aiResponse");
                byte[] body =
                    response.has("bodyFile")
                        ? raw(response.path("bodyFile").asText())
                        : JSON.writeValueAsBytes(response.get("body"));
                int status = response.path("httpStatus").asInt();
                AiFailureCode failure = status != 200 ? AiFailureCode.AI_SERVICE_UNAVAILABLE : null;
                if (scenario.path("caseId").asText().equals("C-invalid-ai-200"))
                  failure = AiFailureCode.AI_INVALID_RESPONSE;
                exchange(
                    contract.parseRequest(raw(scenario.path("request").path("bodyFile").asText())),
                    status,
                    body,
                    failure);
              }));
    }
    assertThat(tests).hasSize(25);
    return tests;
  }

  @TestFactory
  List<DynamicTest> allowsOnlyAgreedStatusAndCodePairs() throws Exception {
    List<DynamicTest> tests = new ArrayList<>();
    var mappings = metadata("error-mapping.json").path("mappings");
    for (JsonNode entry : mappings) {
      String code = entry.path("aiCode").asText();
      int status = entry.path("httpStatus").asInt();
      tests.add(
          DynamicTest.dynamicTest(
              status + " " + code,
              () -> {
                var request = contract.parseRequest(raw(BASE));
                exchange(
                    request,
                    status,
                    errorBody(code),
                    AiFailureCode.valueOf(entry.path("beFailureCode").asText()));
                for (int other :
                    List.of(201, 204, 302, 400, 401, 403, 409, 422, 500, 502, 503, 504)) {
                  if (other != status)
                    exchange(request, other, errorBody(code), AiFailureCode.AI_SERVICE_UNAVAILABLE);
                }
              }));
    }
    return tests;
  }

  @Test
  void rejectsMalformedResponsesWithoutRetainingPrivateBodies() throws Exception {
    var request = contract.parseRequest(raw(BASE));
    for (String body :
        List.of(
            "",
            "null",
            "[]",
            "private-source",
            "<html>private-source</html>",
            "{\"code\":\"AI_TIMEOUT\"}",
            "{\"code\":\"AI_TIMEOUT\",\"message\":null}",
            "{\"code\":\"AI_TIMEOUT\",\"message\":\"private-source\",\"extra\":1}",
            "{\"code\":\"AI_TIMEOUT\",\"code\":\"AI_TIMEOUT\",\"message\":\"private-source\"}")) {
      exchange(
          request,
          504,
          body.getBytes(StandardCharsets.UTF_8),
          AiFailureCode.AI_SERVICE_UNAVAILABLE);
      exchange(
          request, 200, body.getBytes(StandardCharsets.UTF_8), AiFailureCode.AI_INVALID_RESPONSE);
    }
    byte[] invalidUtf8 = {(byte) 0xc3, 0x28};
    exchange(request, 200, invalidUtf8, AiFailureCode.AI_INVALID_RESPONSE);
    String empty = new String(raw("payloads/responses/empty.json"), StandardCharsets.UTF_8);
    exchange(
        request,
        200,
        (empty + " {}").getBytes(StandardCharsets.UTF_8),
        AiFailureCode.AI_INVALID_RESPONSE);
    exchange(
        request,
        200,
        empty
            .replace("\"suggestions\":", "\"suggestions\":[],\"suggestions\":")
            .getBytes(StandardCharsets.UTF_8),
        AiFailureCode.AI_INVALID_RESPONSE);
  }

  @Test
  void reportsTransportFailureAndTimeoutWithoutRetryOrFallback() throws Exception {
    var request = contract.parseRequest(raw(BASE));
    for (IOException exception :
        List.of(
            new SocketTimeoutException("private-source"),
            new java.net.http.HttpTimeoutException("private-source"),
            new ConnectException("private-source"),
            new IOException("private-source"))) {
      var expected =
          exception instanceof SocketTimeoutException
                  || exception instanceof java.net.http.HttpTimeoutException
              ? AiFailureCode.AI_TIMEOUT
              : AiFailureCode.AI_SERVICE_UNAVAILABLE;
      callOnce(request, withException(exception), expected);
    }
  }

  @Test
  void validatesConstructedRequestBeforeAnyHttpCall() throws Exception {
    var request = contract.parseRequest(raw(BASE));
    var invalid =
        new AiV2AnalysisRequest(
            request.requestId(),
            request.careSpaceId(),
            request.recordId(),
            " ",
            request.recordedAt(),
            request.timezone(),
            request.candidates());
    var builder = builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    var client = new HanmaumAiV2Client(builder.build(), SETTINGS, AI);
    assertFailure(
        catchThrowable(() -> client.analyze(invalid)), AiFailureCode.AI_INTEGRATION_ERROR);
    assertFailure(catchThrowable(() -> client.analyze(null)), AiFailureCode.AI_INTEGRATION_ERROR);
    server.verify();
  }

  private void checkReceiptResponse(JsonNode expected) throws Exception {
    int status = expected.path("httpStatus").asInt();
    byte[] body =
        expected.has("bodyFile")
            ? raw(expected.path("bodyFile").asText())
            : errorBody(expected.path("code").asText());
    AiFailureCode failure = null;
    if (status != 200) {
      JsonNode matched = null;
      for (JsonNode mapping : metadata("error-mapping.json").path("mappings")) {
        if (mapping.path("httpStatus").asInt() == status
            && mapping.path("aiCode").asText().equals(expected.path("code").asText())) {
          matched = mapping;
          break;
        }
      }
      assertThat(matched).isNotNull();
      failure = AiFailureCode.valueOf(matched.path("beFailureCode").asText());
    }
    exchange(contract.parseRequest(raw(BASE)), status, body, failure);
  }

  private void exchange(
      AiV2AnalysisRequest request, int status, byte[] body, AiFailureCode failure) {
    callOnce(
        request,
        withStatus(HttpStatusCode.valueOf(status))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body),
        failure);
  }

  private void callOnce(
      AiV2AnalysisRequest request, ResponseCreator response, AiFailureCode failure) {
    var builder = builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    var client = new HanmaumAiV2Client(builder.build(), SETTINGS, AI);
    server
        .expect(requestTo("http://ai.test/v2/analyses"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(header("X-Internal-Api-Key", "test-only-key"))
        .andExpect(header("X-Request-Id", request.requestId().toString()))
        .andExpect(
            sent -> {
              JsonNode body = JSON.readTree(((MockClientHttpRequest) sent).getBodyAsBytes());
              assertThat(uuidValues(body)).isEqualTo(uuidValues(JSON.valueToTree(request)));
              assertThat(UUID.fromString(sent.getHeaders().getFirst("X-Request-Id")))
                  .isEqualTo(UUID.fromString(body.path("requestId").asText()));
            })
        .andRespond(response);
    if (failure == null)
      assertThat(client.analyze(request).requestId()).isEqualTo(request.requestId());
    else assertFailure(catchThrowable(() -> client.analyze(request)), failure);
    server.verify(); // Any second request, including a v1 fallback, fails the expectation.
  }

  private RestClient.Builder builder() {
    return RestClient.builder()
        .baseUrl("http://ai.test")
        .defaultHeader("X-Internal-Api-Key", "test-only-key");
  }

  private byte[] errorBody(String code) {
    return JSON.writeValueAsBytes(
        JSON.createObjectNode().put("code", code).put("message", "private-source diagnostic"));
  }
}
