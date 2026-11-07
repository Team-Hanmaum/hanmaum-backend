package com.hanmaum.backend.careitem.dto;

import java.time.Instant;
import java.util.UUID;

public record CareItemReconfirmationResponse(
    UUID itemId, String version, boolean needsReconfirmation, Instant updatedAt) {}
