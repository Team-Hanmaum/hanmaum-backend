package com.hanmaum.backend.record.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DeleteSourceRecordResponse(
    UUID recordId,
    Instant deletedAt,
    List<UUID> affectedItemIds,
    List<UUID> reconfirmationItemIds) {}
