package com.hanmaum.backend.carespace.entity;

import java.time.Instant;
import java.util.UUID;

public record SpaceMembership(
    UUID id, UUID spaceId, UUID userId, Instant joinedAt, Instant endedAt, String endReason) {}
