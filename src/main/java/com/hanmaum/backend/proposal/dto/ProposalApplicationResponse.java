package com.hanmaum.backend.proposal.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProposalApplicationResponse(UUID batchId, Instant appliedAt, List<Result> results) {
  public record Result(UUID proposalId, UUID itemId, String itemVersion) {}
}
