package com.hanmaum.backend.carespace.entity;

import java.time.Instant;
import java.util.UUID;

public record Invitation(
    UUID id,
    UUID spaceId,
    UUID issuedByMembershipId,
    String tokenHash,
    String encryptedUrl,
    Instant issuedAt,
    Instant expiresAt) {}
