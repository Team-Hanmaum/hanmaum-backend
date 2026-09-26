package com.hanmaum.backend.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AiAnalysisRequest(
    @NotNull UUID requestId,
    @NotNull UUID careSpaceId,
    @NotNull UUID recordId,
    @NotBlank @Size(max = 10000) String content,
    @NotNull Instant recordedAt,
    @NotBlank String timezone,
    @NotNull @Size(max = 50) List<@NotNull @Valid CandidateItem> candidates) {
  public record CandidateItem(
      @NotNull UUID itemId,
      @NotNull @PositiveOrZero Long version,
      @NotNull CareItemType type,
      @NotBlank @Size(max = 2000) String summary) {}
}
