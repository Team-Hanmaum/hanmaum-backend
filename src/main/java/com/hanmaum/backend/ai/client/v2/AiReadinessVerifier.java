package com.hanmaum.backend.ai.client.v2;

import java.nio.charset.StandardCharsets;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in startup check using the same authenticated TLS transport as v2 analyses. */
final class AiReadinessVerifier {
  private static final String FAILURE = "AI v2 TLS/authentication/readiness verification failed";
  private final RestClient client;

  AiReadinessVerifier(RestClient client) {
    this.client = client;
  }

  void verify() {
    try {
      boolean ready =
          Boolean.TRUE.equals(
              client
                  .get()
                  .uri("/health/ready/v2")
                  .exchange(
                      (request, response) -> {
                        if (response.getStatusCode().value() != 200) return false;
                        byte[] body = response.getBody().readNBytes(1025);
                        if (body.length > 1024) return false;
                        var value =
                            JsonMapper.builder()
                                .build()
                                .readTree(new String(body, StandardCharsets.UTF_8));
                        return value.isObject()
                            && value.size() == 2
                            && "UP".equals(value.path("status").asText())
                            && "openai".equals(value.path("provider").asText());
                      }));
      if (!ready) throw new IllegalStateException(FAILURE);
    } catch (Exception ignored) {
      // Never retain a transport exception cause: it may contain a response body or secrets.
      throw new IllegalStateException(FAILURE);
    }
  }
}
