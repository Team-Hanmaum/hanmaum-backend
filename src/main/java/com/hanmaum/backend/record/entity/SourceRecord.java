package com.hanmaum.backend.record.entity;

import java.time.Instant;
import java.util.UUID;

/** JDBC persistence model, not a public response or a JPA entity. */
public record SourceRecord(
    UUID id,
    UUID spaceId,
    UUID authorMembershipId,
    String body,
    Instant recordedAt,
    long version) {}
