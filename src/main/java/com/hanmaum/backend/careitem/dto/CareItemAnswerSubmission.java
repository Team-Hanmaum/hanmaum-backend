package com.hanmaum.backend.careitem.dto;

import java.time.Instant;
import java.util.UUID;

public record CareItemAnswerSubmission(
    UUID answerId,
    UUID requestId,
    UUID requestEntryId,
    String status,
    String questionStatus,
    Instant submittedAt) {}
