package com.hanmaum.backend.proposal.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProposalDeclineResponse(
    UUID batchId, List<UUID> declinedProposalIds, Instant decidedAt) {}
