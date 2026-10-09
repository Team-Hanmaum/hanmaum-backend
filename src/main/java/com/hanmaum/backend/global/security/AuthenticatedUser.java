package com.hanmaum.backend.global.security;

import java.util.Objects;
import java.util.UUID;

/** Request-scoped identity only; does not represent membership or ownership of any care space. */
public record AuthenticatedUser(UUID userId) {
  public AuthenticatedUser {
    Objects.requireNonNull(userId, "userId");
  }
}
