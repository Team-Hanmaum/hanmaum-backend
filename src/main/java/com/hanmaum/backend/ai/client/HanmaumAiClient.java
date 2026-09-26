package com.hanmaum.backend.ai.client;

import com.hanmaum.backend.ai.dto.AiAnalysisRequest;
import com.hanmaum.backend.ai.dto.AiAnalysisResponse;
import com.hanmaum.backend.ai.dto.AiAnalysisResponse.Operation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    Map<UUID, AiAnalysisRequest.CandidateItem> candidates =
        request.candidates().stream()
            .collect(
                Collectors.toMap(AiAnalysisRequest.CandidateItem::itemId, Function.identity()));
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
      if (response == null
          || !validator.validate(response).isEmpty()
          || !request.requestId().equals(response.requestId())) {
        throw new AiServiceException();
      }
      for (var suggestion : response.suggestions()) {
        if (!request.content().contains(suggestion.evidence())) {
          throw new AiServiceException();
        }
        if (suggestion.candidateItemIds().stream().anyMatch(id -> !candidates.containsKey(id))) {
          throw new AiServiceException();
        }
        if (suggestion.operation() == Operation.CREATE) {
          if (suggestion.targetItemId() != null
              || suggestion.expectedVersion() != null
              || !suggestion.candidateItemIds().isEmpty()) {
            throw new AiServiceException();
          }
        } else if (suggestion.targetItemId() != null) {
          var target = candidates.get(suggestion.targetItemId());
          if (target == null
              || target.type() != suggestion.type()
              || !Objects.equals(target.version(), suggestion.expectedVersion())) {
            throw new AiServiceException();
          }
        } else if (suggestion.expectedVersion() != null
            || suggestion.candidateItemIds().isEmpty()) {
          throw new AiServiceException();
        }
      }
      return response;
    } catch (RestClientException exception) {
      // Do not expose upstream bodies, which may contain source records or provider details.
      throw new AiServiceException();
    }
  }
}
