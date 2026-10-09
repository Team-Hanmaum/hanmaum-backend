package com.hanmaum.backend.user.repository;

import com.hanmaum.backend.user.entity.SocialProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SocialAccountIdentityLockRepository {
  private final JdbcTemplate jdbc;

  public SocialAccountIdentityLockRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The caller must hold a transaction; PostgreSQL releases this lock on commit or rollback. */
  public void lock(SocialProvider provider, String providerUserId) {
    // A hash collision only serializes unrelated logins; lookup still uses the full identity.
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        resultSet -> {},
        "hanmaum:social-login:" + provider.name() + ":" + providerUserId);
  }
}
