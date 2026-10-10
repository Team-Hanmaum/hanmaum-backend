package com.hanmaum.backend.carespace.entity;

import java.time.Instant;
import java.util.UUID;

/** Immutable persistence model. SQL constraints enforce the circular owner/membership relation. */
public record CareSpace(
    UUID id,
    UUID ownerMembershipId,
    UUID ownerUserId,
    String subjectLabel,
    long version,
    String lifecycle,
    Instant createdAt,
    Instant updatedAt) {}
