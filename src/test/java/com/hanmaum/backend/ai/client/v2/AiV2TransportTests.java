package com.hanmaum.backend.ai.client.v2;

import static com.hanmaum.backend.ai.client.v2.V2Fixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.hanmaum.backend.ai.client.AiProperties;
import com.hanmaum.backend.ai.code.AiFailureCode;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisResponse;
import com.hanmaum.backend.ai.fixture.V2GeneratedPayloads;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.web.client.RestClient;

/** Real loopback HTTP with production client configuration; no AI server or paid provider. */
class AiV2TransportTests {
  // These 14 require the AI runtime. BE mapping checks are not receipt/slot execution.
  static final Set<String> AI_RUNTIME_CASES =
      Set.of(
          "H-existing-before-overload-same",
          "H-existing-before-overload-different",
          "H-existing-before-overload-uncomparable",
          "H-new-overloaded",
          "H-registration-race-same",
          "H-registration-race-different",
          "H-overload-concurrent-commit",
          "H-slot-retained-timeout",
          "H-slot-retained-caller-cancel",
          "H-chunked-request-at",
          "H-chunked-request-above",
          "H-receipt-preserved-response-bytes",
          "H-receipt-preserved-response-suggestions",
          "H-receipt-preserved-response-changeText");
  private HttpServer server;
  private java.util.concurrent.ExecutorService executor;
  private HttpClient http;
  private final AtomicInteger calls = new AtomicInteger();
  private final AiV2Contract contract = new AiV2Contract(LIMITS);

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.start();
  }

  @AfterEach
  void stop() {
    if (http != null) http.shutdownNow();
    server.stop(0);
    executor.shutdownNow();
  }

  @Test
  void receivesACompleteBodyWithinDeadlineWithMatchingHeadersAndRequest() throws Exception {
    var received = new AtomicReference<byte[]>();
    var requestId = new AtomicReference<String>();
    var apiKey = new AtomicReference<String>();
    byte[] response = raw("payloads/responses/create-schedule.json");
    server.createContext(
        "/v2/analyses",
        exchange -> {
          calls.incrementAndGet();
          received.set(exchange.getRequestBody().readAllBytes());
          requestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
          apiKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
          exchange.sendResponseHeaders(200, response.length);
          try (var body = exchange.getResponseBody()) {
            body.write(response);
          }
        });
    var request = contract.parseRequest(raw(BASE));
    byte[] encoded = contract.encodeRequest(request);
    var client = client(Duration.ofSeconds(3), encoded.length, response.length);
    var actual = client.analyze(request);
    assertThat(actual.suggestions()).hasSize(1);
    assertResponseMatchesFixture(actual, response);
    assertThat(received.get()).isEqualTo(encoded);
    assertThat(requestId.get()).isEqualTo(request.requestId().toString());
    assertThat(apiKey.get()).isEqualTo("test-only-key");
    assertThat(calls).hasValue(1);
  }

  @Test
  void sendsHttp11WithoutAnH2cUpgradeToTheAiServer() throws Exception {
    var protocol = new AtomicReference<String>();
    var upgrade = new AtomicReference<String>();
    var http2Settings = new AtomicReference<String>();
    byte[] response = raw("payloads/responses/create-schedule.json");
    server.createContext(
        "/v2/analyses",
        exchange -> {
          calls.incrementAndGet();
          protocol.set(exchange.getProtocol());
          upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
          http2Settings.set(exchange.getRequestHeaders().getFirst("HTTP2-Settings"));
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, response.length);
          try (var output = exchange.getResponseBody()) {
            output.write(response);
          }
        });
    var actual =
        client(Duration.ofSeconds(3), LIMITS.responseBytes())
            .analyze(contract.parseRequest(raw(BASE)));
    assertResponseMatchesFixture(actual, response);
    assertThat(protocol.get()).isEqualTo("HTTP/1.1");
    assertThat(upgrade.get()).isNull();
    assertThat(http2Settings.get()).isNull();
    assertThat(calls).hasValue(1);
  }

  @TestFactory
  List<DynamicTest> executesElevenSharedBeHttpCasesWithoutClaimingDatabaseEffects()
      throws Exception {
    var tests = new ArrayList<DynamicTest>();
    var deferred = new java.util.HashSet<String>();
    for (var scenario : metadata("cases/http.json")) {
      String id = scenario.path("caseId").asText();
      if (AI_RUNTIME_CASES.contains(id)) {
        deferred.add(id);
        continue;
      }
      tests.add(
          DynamicTest.dynamicTest(
              id + " BE transport only",
              () -> {
                calls.set(0);
                var setup = scenario.path("setup");
                var expected = scenario.path("expected");
                if (id.equals("H-be-preflight-limit")) {
                  byte[] padded = V2GeneratedPayloads.raw(setup.path("requestBodyRecipe").asText());
                  assertThat(padded.length).isEqualTo(LIMITS.requestBytes() + 1);
                  assertFailure(
                      catchThrowable(() -> contract.parseRequest(padded)),
                      AiFailureCode.AI_INTEGRATION_ERROR);
                  // The recipe exceeds 1 MiB through trailing whitespace, which a DTO cannot keep.
                  // Prepare a valid DTO outside the failure assertion, then test the actual client
                  // with a budget one byte below its serialized size. Shared limits stay unchanged.
                  var request =
                      contract.parseRequest(JSON.writeValueAsBytes(JSON.readTree(padded)));
                  byte[] encoded = contract.encodeRequest(request);
                  int requestBudget = encoded.length - 1;
                  assertThat(requestBudget).isPositive();
                  server.createContext(
                      "/v2/analyses",
                      exchange -> {
                        calls.incrementAndGet();
                        exchange.close();
                      });
                  try {
                    var client =
                        client(Duration.ofSeconds(3), requestBudget, LIMITS.responseBytes());
                    assertFailure(
                        catchThrowable(() -> client.analyze(request)),
                        AiFailureCode.AI_INTEGRATION_ERROR);
                    assertThat(calls).hasValue(0);
                  } finally {
                    server.removeContext("/v2/analyses");
                  }
                  return;
                }
                int status =
                    setup.has("injectedResponse")
                        ? setup.path("injectedResponse").path("httpStatus").asInt()
                        : setup.path("injectedResponseStatus").asInt(200);
                byte[] response =
                    setup.has("injectedResponse")
                        ? JSON.writeValueAsBytes(setup.path("injectedResponse").path("body"))
                        : V2GeneratedPayloads.raw(
                            setup.has("bodyRecipe")
                                ? setup.path("bodyRecipe").asText()
                                : setup.path("responseBodyRecipe").asText());
                server.createContext(
                    "/v2/analyses",
                    exchange -> {
                      calls.incrementAndGet();
                      exchange.getRequestBody().readAllBytes();
                      exchange.getResponseHeaders().set("Content-Type", "application/json");
                      exchange.sendResponseHeaders(
                          status, 0); // Real chunked response, no Content-Length.
                      try (var output = exchange.getResponseBody()) {
                        int chunk = setup.path("chunkBytes").asInt(8192);
                        for (int offset = 0; offset < response.length; offset += chunk)
                          output.write(response, offset, Math.min(chunk, response.length - offset));
                      } catch (java.io.IOException ignored) {
                        /* Expected early close for oversized bodies. */
                      }
                    });
                try {
                  var client = client(Duration.ofSeconds(3), LIMITS.responseBytes());
                  var request =
                      contract.parseRequest(
                          setup.has("requestBodyRecipe")
                              ? V2GeneratedPayloads.raw(setup.path("requestBodyRecipe").asText())
                              : raw(BASE));
                  var failure =
                      expected.has("be")
                          ? expected.path("be").path("failureCode")
                          : expected.path("failureCode");
                  if (failure.isNull())
                    assertResponseMatchesFixture(client.analyze(request), response);
                  else
                    assertFailure(
                        catchThrowable(() -> client.analyze(request)),
                        AiFailureCode.valueOf(failure.asText()));
                  assertThat(calls).hasValue(1); // Includes no retry/fallback on the same server.
                } finally {
                  server.removeContext("/v2/analyses");
                }
              }));
    }
    assertThat(deferred).containsExactlyInAnyOrderElementsOf(AI_RUNTIME_CASES);
    assertThat(tests).hasSize(11);
    return tests;
  }

  @Test
  void endsAContinuouslyTricklingBodyAtTheOverallDeadlineAndReleasesConnection() throws Exception {
    var disconnected = new CountDownLatch(1);
    var chunks = new AtomicInteger();
    server.createContext(
        "/v2/analyses",
        exchange -> {
          calls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, 0);
          try (var body = exchange.getResponseBody()) {
            for (int i = 0; i < 200; i++) {
              body.write(' ');
              body.flush();
              chunks.incrementAndGet();
              Thread.sleep(
                  20); // Far shorter than the overall budget; no idle timeout can solve this.
            }
          } catch (java.io.IOException exception) {
            disconnected.countDown();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
        });
    var client = client(Duration.ofMillis(400), LIMITS.responseBytes());
    var request = contract.parseRequest(raw(BASE));
    long started = System.nanoTime();
    assertFailure(catchThrowable(() -> client.analyze(request)), AiFailureCode.AI_TIMEOUT);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    assertThat(chunks.get()).isGreaterThan(2);
    assertThat(disconnected.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(calls).hasValue(1);
  }

  @Test
  void timesOutBeforeHeadersWithoutRetry() throws Exception {
    server.createContext(
        "/v2/analyses",
        exchange -> {
          calls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          try {
            Thread.sleep(2000);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    var client = client(Duration.ofMillis(300), LIMITS.responseBytes());
    var request = contract.parseRequest(raw(BASE));
    assertFailure(catchThrowable(() -> client.analyze(request)), AiFailureCode.AI_TIMEOUT);
    assertThat(calls).hasValue(1);
  }

  @Test
  void rejectsDeclaredAndChunkedOversizedResponsesWithoutRetry() throws Exception {
    var declared = new java.util.concurrent.atomic.AtomicBoolean(true);
    server.createContext(
        "/v2/analyses",
        exchange -> {
          calls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, declared.get() ? 1024 : 0);
          try (var body = exchange.getResponseBody()) {
            body.write(new byte[1024]);
          } catch (java.io.IOException ignored) {
            /* Expected cancellation on budget violation. */
          }
        });
    var client = client(Duration.ofSeconds(3), 64);
    var request = contract.parseRequest(raw(BASE));
    assertFailure(catchThrowable(() -> client.analyze(request)), AiFailureCode.AI_INVALID_RESPONSE);
    declared.set(false);
    assertFailure(catchThrowable(() -> client.analyze(request)), AiFailureCode.AI_INVALID_RESPONSE);
    assertThat(calls).hasValue(2);
  }

  @Test
  void exposesAllRedirectStatusesWithoutFollowingOrSendingCredentialsToTarget() throws Exception {
    var targetCalls = new AtomicInteger();
    var status = new AtomicInteger();
    server.createContext(
        "/target",
        exchange -> {
          targetCalls.incrementAndGet();
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.createContext(
        "/v2/analyses",
        exchange -> {
          calls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange.getResponseHeaders().set("Location", "/target");
          exchange.sendResponseHeaders(status.get(), -1);
          exchange.close();
        });
    var client = client(Duration.ofSeconds(3), LIMITS.responseBytes());
    var request = contract.parseRequest(raw(BASE));
    for (int code : java.util.List.of(301, 302, 303, 307, 308)) {
      status.set(code);
      assertFailure(
          catchThrowable(() -> client.analyze(request)), AiFailureCode.AI_SERVICE_UNAVAILABLE);
    }
    assertThat(calls).hasValue(5);
    assertThat(targetCalls).hasValue(0);
  }

  private HanmaumAiV2Client client(Duration timeout, int responseBudget) {
    return client(timeout, LIMITS.requestBytes(), responseBudget);
  }

  private HanmaumAiV2Client client(Duration timeout, int requestBudget, int responseBudget) {
    if (http != null) http.shutdownNow();
    var ai =
        new AiProperties(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "test-only-key",
            Duration.ofSeconds(3),
            timeout);
    var config = new AiV2ClientConfig();
    http = config.hanmaumAiV2HttpClient(ai);
    return new HanmaumAiV2Client(
        config.hanmaumAiV2RestClient(RestClient.builder(), ai, http),
        new AiV2Properties(requestBudget, responseBudget),
        ai);
  }

  private static void assertResponseMatchesFixture(AiV2AnalysisResponse actual, byte[] response) {
    // Compare against the injected JSON, independently of the production response parser.
    assertThat(uuidValues(JSON.valueToTree(actual))).isEqualTo(uuidValues(JSON.readTree(response)));
  }
}
