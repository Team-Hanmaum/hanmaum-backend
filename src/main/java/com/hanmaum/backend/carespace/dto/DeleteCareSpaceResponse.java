package com.hanmaum.backend.carespace.dto;

import java.time.Instant;
import java.util.UUID;

public record DeleteCareSpaceResponse(UUID spaceId, Instant deletedAt) {}
