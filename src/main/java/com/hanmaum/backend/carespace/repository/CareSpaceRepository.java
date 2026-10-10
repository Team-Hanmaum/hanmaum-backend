package com.hanmaum.backend.carespace.repository;

import com.hanmaum.backend.carespace.entity.CareSpace;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CareSpaceRepository {
  private final JdbcTemplate jdbc;

  public CareSpaceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean insertIfLabelAvailable(CareSpace space) {
    // The unique index arbitrates concurrent requests, including those on different servers.
    return jdbc.update(
            """
        INSERT INTO care_space
          (id, owner_membership_id, owner_user_id, subject_label, version, lifecycle, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT ON CONSTRAINT care_space_owner_label_uq DO NOTHING
        """,
            space.id(),
            space.ownerMembershipId(),
            space.ownerUserId(),
            space.subjectLabel(),
            space.version(),
            space.lifecycle(),
            Timestamp.from(space.createdAt()),
            Timestamp.from(space.updatedAt()))
        == 1;
  }
}
