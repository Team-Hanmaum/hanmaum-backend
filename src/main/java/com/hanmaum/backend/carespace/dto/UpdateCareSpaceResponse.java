package com.hanmaum.backend.carespace.dto;

import java.time.Instant;
import java.util.UUID;

public record UpdateCareSpaceResponse(
    UUID spaceId, String subjectLabel, String version, Instant updatedAt) {}
