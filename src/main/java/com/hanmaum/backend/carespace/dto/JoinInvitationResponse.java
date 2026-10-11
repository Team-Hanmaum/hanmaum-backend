package com.hanmaum.backend.carespace.dto;

import java.time.Instant;
import java.util.UUID;

public record JoinInvitationResponse(
    UUID spaceId, UUID membershipId, CareSpaceRole role, Instant joinedAt) {}
