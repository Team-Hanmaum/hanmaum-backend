package com.hanmaum.backend.carespace.repository;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class MembershipLifecycleRepository {
  private final JdbcTemplate jdbc;

  public MembershipLifecycleRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The caller holds the space lock before locking any target membership. */
  public TargetMembership lockTarget(UUID spaceId, UUID membershipId) {
    return jdbc
        .query(
            """
        SELECT user_id, is_active FROM space_membership WHERE space_id = ? AND id = ? FOR UPDATE
        """,
            (rs, row) ->
                new TargetMembership(
                    rs.getObject("user_id", UUID.class), rs.getBoolean("is_active")),
            spaceId,
            membershipId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
  }

  public void transfer(UUID spaceId, UUID membershipId, UUID userId, Instant now) {
    // The unique owner/label index arbitrates creation/transfer races across different spaces.
    jdbc.update(
        """
        UPDATE care_space SET owner_membership_id = ?, owner_user_id = ?, version = version + 1,
          updated_at = ? WHERE id = ?
        """,
        membershipId,
        userId,
        Timestamp.from(now),
        spaceId);
  }

  public void end(UUID spaceId, UUID membershipId, String reason, Instant now) {
    jdbc.update(
        """
        UPDATE space_membership SET ended_at = ?, end_reason = ? WHERE space_id = ? AND id = ?
        """,
        Timestamp.from(now),
        reason,
        spaceId,
        membershipId);
    jdbc.update(
        "UPDATE care_space SET version = version + 1, updated_at = ? WHERE id = ?",
        Timestamp.from(now),
        spaceId);
  }

  public record TargetMembership(UUID userId, boolean active) {}
}
