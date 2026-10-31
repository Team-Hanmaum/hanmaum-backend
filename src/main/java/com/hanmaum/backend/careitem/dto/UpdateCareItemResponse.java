package com.hanmaum.backend.careitem.dto;

import java.time.Instant;
import java.util.UUID;

public record UpdateCareItemResponse(UUID itemId, String version, Instant updatedAt) {}
