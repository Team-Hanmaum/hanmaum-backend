package com.hanmaum.backend.ai.client.v2;

import static org.assertj.core.api.Assertions.*;

import com.hanmaum.backend.ai.client.AiProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class AiReadinessVerifierTests {
  private HttpServer server;
  private int status = 200;
  private String body = "{\"provider\":\"openai\",\"status\":\"UP\"}";
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<String> path = new AtomicReference<>();
  private final AtomicReference<String> key = new AtomicReference<>();

  @BeforeEach
  void startLoopback() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          path.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
          key.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.getResponseHeaders().set("Location", "/must-not-follow");
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopLoopback() {
    server.stop(0);
  }

  private ApplicationContextRunner context() {
    var properties =
        new AiProperties(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "synthetic-internal-key",
            Duration.ofSeconds(1),
            Duration.ofSeconds(1));
    return new ApplicationContextRunner()
        .withUserConfiguration(AiV2ClientConfig.class)
        .withBean(AiProperties.class, () -> properties)
        .withBean(RestClient.Builder.class, RestClient::builder);
  }

  @Test
  void disabledByDefaultWithoutAnyNetworkRequest() {
    context().run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(AiReadinessVerifier.class));
    assertThat(calls).hasValue(0);
  }

  @Test
  void authenticatedReadinessGatesStartupWithoutAnalysisCalls() {
    context()
        .withPropertyValues("app.ai.verify-on-startup=true")
        .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(AiReadinessVerifier.class));
    assertThat(calls).hasValue(1);
    assertThat(path).hasValue("GET /health/ready/v2");
    assertThat(key).hasValue("synthetic-internal-key");
  }

  static Stream<Arguments> failures() {
    return Stream.of(
        Arguments.of(401, "synthetic-provider-secret"),
        Arguments.of(503, "{\"status\":\"DOWN\"}"),
        Arguments.of(302, "redirect"),
        Arguments.of(200, "{\"status\":\"UP\",\"provider\":\"stub\"}"),
        Arguments.of(200, "{\"status\":\"DOWN\",\"provider\":\"openai\"}"),
        Arguments.of(200, "not-json-synthetic-secret"),
        Arguments.of(200, " ".repeat(1025)),
        Arguments.of(200, "null"));
  }

  @ParameterizedTest
  @MethodSource("failures")
  void invalidReadinessStopsStartupWithoutLeakingResponses(int code, String response) {
    status = code;
    body = response;
    context()
        .withPropertyValues("app.ai.verify-on-startup=true")
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              assertThat(ctx.getStartupFailure())
                  .hasRootCauseExactlyInstanceOf(IllegalStateException.class)
                  .hasRootCauseMessage("AI v2 TLS/authentication/readiness verification failed");
              var trace = new java.io.StringWriter();
              ctx.getStartupFailure().printStackTrace(new java.io.PrintWriter(trace));
              assertThat(trace.toString())
                  .doesNotContain("synthetic-provider-secret", "not-json-synthetic-secret");
            });
    assertThat(calls).hasValue(1);
    assertThat(path).hasValue("GET /health/ready/v2");
  }
}
