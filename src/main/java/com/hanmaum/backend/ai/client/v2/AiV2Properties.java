package com.hanmaum.backend.ai.client.v2;

import com.hanmaum.backend.ai.code.AiFailureCode;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.ai.v2")
public record AiV2Properties(
    @Positive Integer maxRequestBytes, @Positive Integer maxResponseBytes) {
  public AiV2PayloadLimits requireLimits() {
    if (maxRequestBytes == null
        || maxResponseBytes == null
        || maxRequestBytes < 1
        || maxResponseBytes < 1) {
      throw new AiV2Exception(AiFailureCode.AI_INTEGRATION_ERROR);
    }
    return new AiV2PayloadLimits(maxRequestBytes, maxResponseBytes);
  }
}
