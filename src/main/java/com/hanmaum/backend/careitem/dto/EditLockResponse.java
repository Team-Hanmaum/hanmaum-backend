package com.hanmaum.backend.careitem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record EditLockResponse(
    UUID itemId,
    @Schema(nullable = true, description = "획득 응답에만 제공. 갱신 시 null") String leaseToken,
    UUID holderMembershipId,
    Instant expiresAt,
    long renewAfterSeconds) {
  @Override
  public String toString() {
    return "EditLockResponse[itemId=" + itemId + "]";
  }
}
