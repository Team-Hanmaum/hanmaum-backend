package com.hanmaum.backend.ai.client;

import com.hanmaum.backend.ai.dto.AiAnalysisRequest;
import com.hanmaum.backend.ai.dto.AiAnalysisResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HanmaumAiClient {
  private final RestClient client;
  private final Validator validator;

  public HanmaumAiClient(@Qualifier("hanmaumAiRestClient") RestClient client, Validator validator) {
    this.client = client;
    this.validator = validator;
  }

  // Call outside a DB transaction. The result is an unconfirmed proposal, never a write command.
  public AiAnalysisResponse analyze(AiAnalysisRequest request) {
    var violations = validator.validate(request);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(violations);
    }
    ZoneId.of(request.timezone());
    try {
      var response =
          client
              .post()
              .uri("/v1/analyses")
              .contentType(MediaType.APPLICATION_JSON)
              .header("X-Request-Id", request.requestId().toString())
              .body(request)
              .retrieve()
              .body(AiAnalysisResponse.class);
      new AiAnalysisResultValidator(validator).validate(request, response);
      return response;
    } catch (RestClientException exception) {
      // Do not expose upstream bodies, which may contain source records or provider details.
      throw new AiServiceException();
    }
  }
}
