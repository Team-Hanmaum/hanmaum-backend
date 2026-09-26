package com.hanmaum.backend.ai.client;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.ai")
public record AiProperties(
    @NotNull URI baseUrl,
    @NotBlank String apiKey,
    @NotNull @DurationMin(millis = 1) Duration connectTimeout,
    @NotNull @DurationMin(millis = 1) Duration readTimeout) {
  // Keep the internal API key out of generated diagnostic strings.
  @Override
  public String toString() {
    return "AiProperties[baseUrl=" + baseUrl + ", apiKey=REDACTED]";
  }
}
