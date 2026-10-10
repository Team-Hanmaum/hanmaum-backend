package com.hanmaum.backend.carespace.repository;

import com.hanmaum.backend.carespace.entity.SpaceMembership;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SpaceMembershipRepository {
  private final JdbcTemplate jdbc;

  public SpaceMembershipRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(SpaceMembership membership) {
    jdbc.update(
        """
        INSERT INTO space_membership (id, space_id, user_id, joined_at, ended_at, end_reason)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        membership.id(),
        membership.spaceId(),
        membership.userId(),
        Timestamp.from(membership.joinedAt()),
        membership.endedAt() == null ? null : Timestamp.from(membership.endedAt()),
        membership.endReason());
  }
}
