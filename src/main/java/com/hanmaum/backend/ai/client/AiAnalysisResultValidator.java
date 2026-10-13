package com.hanmaum.backend.ai.client;

import com.hanmaum.backend.ai.dto.AiAnalysisRequest;
import com.hanmaum.backend.ai.dto.AiAnalysisResponse;
import jakarta.validation.Validator;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Shared boundary validation for HTTP responses and persistence. */
@Component
public class AiAnalysisResultValidator {
  private final Validator validator;

  public AiAnalysisResultValidator(Validator validator) {
    this.validator = validator;
  }

  public void validate(AiAnalysisRequest request, AiAnalysisResponse response) {
    if (response == null
        || !validator.validate(response).isEmpty()
        || !request.requestId().equals(response.requestId())) throw new AiServiceException();
    var candidates =
        request.candidates().stream()
            .collect(
                Collectors.toMap(AiAnalysisRequest.CandidateItem::itemId, Function.identity()));
    for (var suggestion : response.suggestions()) {
      if (!request.content().contains(suggestion.evidence())
          || suggestion.candidateItemIds().stream().anyMatch(id -> !candidates.containsKey(id)))
        throw new AiServiceException();
      if (suggestion.operation() == AiAnalysisResponse.Operation.CREATE) {
        if (suggestion.targetItemId() != null
            || suggestion.expectedVersion() != null
            || !suggestion.candidateItemIds().isEmpty()) throw new AiServiceException();
      } else if (suggestion.targetItemId() != null) {
        var target = candidates.get(suggestion.targetItemId());
        if (target == null
            || target.type() != suggestion.type()
            || !Objects.equals(target.version(), suggestion.expectedVersion()))
          throw new AiServiceException();
      } else if (suggestion.expectedVersion() != null || suggestion.candidateItemIds().isEmpty())
        throw new AiServiceException();
    }
  }
}
