package com.hanmaum.backend.carespace.dto;

import java.time.Instant;
import java.util.UUID;

public record InvitationPreview(
    UUID invitationId, UUID spaceId, Instant expiresAt, boolean isMember) {}
