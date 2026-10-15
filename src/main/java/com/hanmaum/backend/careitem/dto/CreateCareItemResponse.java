package com.hanmaum.backend.careitem.dto;

import java.time.Instant;
import java.util.UUID;

public record CreateCareItemResponse(
    UUID itemId, String itemType, String version, Instant createdAt) {}
