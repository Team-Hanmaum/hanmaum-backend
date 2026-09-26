package com.hanmaum.backend.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AiAnalysisResponse(
    @NotNull UUID requestId,
    @NotNull @Size(max = 30) List<@NotNull @Valid Suggestion> suggestions) {
  public enum Operation {
    CREATE,
    UPDATE,
    RESOLVE
  }

  public record Suggestion(
      @NotNull Operation operation,
      @NotNull CareItemType type,
      UUID targetItemId,
      @PositiveOrZero Long expectedVersion,
      @NotNull @Size(max = 50) List<@NotNull UUID> candidateItemIds,
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 10000) String evidence,
      @NotNull @Size(max = 10) Map<@NotBlank String, @NotNull @Size(max = 2000) String> changes) {}
}
