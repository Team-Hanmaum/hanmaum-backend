package com.hanmaum.backend.proposal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record ProposalListResponse(
    List<ProposalSummary> items,
    @Schema(nullable = true, description = "마지막 페이지는 null. 같은 공간·필터에만 사용") String nextCursor,
    boolean hasNext) {}
