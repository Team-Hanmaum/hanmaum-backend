package com.hanmaum.backend.carespace.repository;

import com.hanmaum.backend.carespace.dto.CareSpaceDetail;
import com.hanmaum.backend.carespace.dto.CareSpaceRole;
import com.hanmaum.backend.carespace.dto.CareSpaceSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CareSpaceQueryRepository {
  private final JdbcTemplate jdbc;

  public CareSpaceQueryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<CareSpaceSummary> findCurrentSpaces(UUID userId) {
    // Participation, owner role and member count are read in one statement/snapshot (no N+1).
    return jdbc.query(
        """
        SELECT s.id, s.subject_label, m.id AS membership_id, s.owner_membership_id,
          (SELECT count(*) FROM space_membership members WHERE members.space_id = s.id AND members.is_active) AS member_count
        FROM space_membership m JOIN care_space s ON s.id = m.space_id
        WHERE m.user_id = ? AND m.is_active AND s.lifecycle = 'ACTIVE'
        ORDER BY m.joined_at ASC, s.id ASC
        """,
        (rs, row) -> {
          UUID membershipId = rs.getObject("membership_id", UUID.class);
          return new CareSpaceSummary(
              rs.getObject("id", UUID.class),
              rs.getString("subject_label"),
              membershipId,
              role(membershipId, rs.getObject("owner_membership_id", UUID.class)),
              rs.getLong("member_count"));
        },
        userId);
  }

  public Optional<CareSpaceDetail> findDetail(UUID spaceId, UUID userId) {
    // A missing membership stays NULL so the service can distinguish 403 from a missing space.
    return jdbc
        .query(
            """
        SELECT s.*, m.id AS membership_id,
          (SELECT count(*) FROM space_membership members WHERE members.space_id = s.id AND members.is_active) AS member_count
        FROM care_space s LEFT JOIN space_membership m
          ON m.space_id = s.id AND m.user_id = ? AND m.is_active
        WHERE s.id = ? AND s.lifecycle = 'ACTIVE'
        """,
            (rs, row) -> {
              UUID membershipId = rs.getObject("membership_id", UUID.class);
              return new CareSpaceDetail(
                  rs.getObject("id", UUID.class),
                  rs.getString("subject_label"),
                  membershipId,
                  membershipId == null
                      ? null
                      : role(membershipId, rs.getObject("owner_membership_id", UUID.class)),
                  rs.getLong("member_count"),
                  Long.toString(rs.getLong("version")),
                  rs.getTimestamp("created_at").toInstant(),
                  rs.getTimestamp("updated_at").toInstant());
            },
            userId,
            spaceId)
        .stream()
        .findFirst();
  }

  private static CareSpaceRole role(UUID membershipId, UUID ownerMembershipId) {
    return membershipId.equals(ownerMembershipId) ? CareSpaceRole.OWNER : CareSpaceRole.MEMBER;
  }
}
