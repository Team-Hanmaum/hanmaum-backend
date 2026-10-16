package com.hanmaum.backend.ai.client.v2;

import static com.hanmaum.backend.ai.client.v2.V2Fixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.hanmaum.backend.ai.client.AiProperties;
import com.hanmaum.backend.ai.code.AiFailureCode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Real loopback HTTP with production client configuration; no AI server or paid provider. */
class AiV2TransportTests {
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
    byte[] response = raw("payloads/responses/empty.json");
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
    var client = client(Duration.ofSeconds(3), response.length);
    var request = contract.parseRequest(raw(BASE));
    assertThat(client.analyze(request).requestId()).isEqualTo(request.requestId());
    assertThat(received.get()).isEqualTo(contract.encodeRequest(request));
    assertThat(requestId.get()).isEqualTo(request.requestId().toString());
    assertThat(apiKey.get()).isEqualTo("test-only-key");
    assertThat(calls).hasValue(1);
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
        new AiV2Properties(LIMITS.requestBytes(), responseBudget),
        ai);
  }
}
