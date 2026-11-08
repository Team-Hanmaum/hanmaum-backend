package com.hanmaum.backend.user.service;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes account deletion with new participation and OAuth credentials, before space locks. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class MemberLifecycleAccess {
  private final JdbcTemplate jdbc;

  public MemberLifecycleAccess(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Missing members are rejected inside the write transaction, including delayed old requests. */
  public void requireExisting(UUID user) {
    if (!lockAndExists(user)) throw new ApiException(CommonErrorCode.UNAUTHENTICATED);
  }

  /** Non-throwing variant for credential reads that must discard removed member identities. */
  public boolean lockAndExists(UUID user) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        rs -> {},
        "hanmaum:member-lifecycle:" + user);
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM app_user WHERE id=?)", Boolean.class, user));
  }
}
