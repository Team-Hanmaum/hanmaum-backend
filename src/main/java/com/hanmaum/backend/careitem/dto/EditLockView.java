package com.hanmaum.backend.careitem.dto;

import java.time.Instant;
import java.util.UUID;

/** Public editor state, never a lease credential. */
public record EditLockView(UUID holderMembershipId, Instant expiresAt, boolean mine) {}
