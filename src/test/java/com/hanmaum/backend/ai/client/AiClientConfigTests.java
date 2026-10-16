package com.hanmaum.backend.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class AiClientConfigTests {
  @Test
  void existingSimpleTransportAlreadyDoesNotFollowPostRedirects() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var targets = new AtomicInteger();
    var calls = new AtomicInteger();
    var status = new AtomicInteger();
    server.createContext(
        "/target",
        exchange -> {
          targets.incrementAndGet();
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
    server.start();
    try {
      var settings =
          new AiProperties(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              "test-only-key",
              Duration.ofSeconds(3),
              Duration.ofSeconds(3));
      var client = new AiClientConfig().hanmaumAiRestClient(RestClient.builder(), settings);
      for (int code : java.util.List.of(301, 302, 303, 307, 308)) {
        status.set(code);
        int received =
            client
                .post()
                .uri("/v2/analyses")
                .body("{}")
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(received).isEqualTo(code);
      }
      assertThat(calls).hasValue(5);
      assertThat(targets).hasValue(0);
    } finally {
      server.stop(0);
    }
  }
}
