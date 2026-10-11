package com.hanmaum.backend.carespace.repository;

import com.hanmaum.backend.carespace.dto.CareSpaceRole;
import com.hanmaum.backend.carespace.dto.SpaceMemberSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SpaceMemberQueryRepository {
  private final JdbcTemplate jdbc;

  public SpaceMemberQueryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Listing> find(UUID userId, UUID spaceId) {
    // Authorization and membership data share one statement/snapshot. No names are read for
    // outsiders.
    var rows =
        jdbc.query(
            """
        SELECT viewer.id AS viewer_id, m.id AS membership_id, u.display_name,
               m.id = s.owner_membership_id AS is_owner, m.user_id = ? AS is_me
        FROM care_space s
        LEFT JOIN space_membership viewer ON viewer.space_id = s.id AND viewer.user_id = ? AND viewer.is_active
        LEFT JOIN space_membership m ON m.space_id = s.id AND m.is_active AND viewer.id IS NOT NULL
        LEFT JOIN app_user u ON u.id = m.user_id
        WHERE s.id = ? AND s.lifecycle = 'ACTIVE'
        ORDER BY (m.id = s.owner_membership_id) DESC, m.joined_at ASC, m.id ASC
        """,
            (rs, row) -> {
              UUID member = rs.getObject("membership_id", UUID.class);
              return new MemberRow(
                  rs.getObject("viewer_id", UUID.class),
                  member == null
                      ? null
                      : new SpaceMemberSummary(
                          member,
                          rs.getString("display_name"),
                          rs.getBoolean("is_owner") ? CareSpaceRole.OWNER : CareSpaceRole.MEMBER,
                          rs.getBoolean("is_me")));
            },
            userId,
            userId,
            spaceId);
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(
        new Listing(
            rows.getFirst().viewerId() != null,
            rows.stream().map(MemberRow::member).filter(java.util.Objects::nonNull).toList()));
  }

  private record MemberRow(UUID viewerId, SpaceMemberSummary member) {}

  public record Listing(boolean authorized, List<SpaceMemberSummary> members) {}
}
