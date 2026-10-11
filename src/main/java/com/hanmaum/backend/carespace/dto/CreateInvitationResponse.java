package com.hanmaum.backend.carespace.dto;

import java.time.Instant;
import java.util.UUID;

public record CreateInvitationResponse(
    UUID invitationId, UUID spaceId, String invitationUrl, Instant issuedAt, Instant expiresAt) {}
