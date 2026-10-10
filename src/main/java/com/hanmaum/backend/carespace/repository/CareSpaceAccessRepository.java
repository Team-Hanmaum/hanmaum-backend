package com.hanmaum.backend.carespace.repository;

import com.hanmaum.backend.carespace.dto.CareSpaceAccess;
import com.hanmaum.backend.carespace.dto.CareSpaceRole;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class CareSpaceAccessRepository {
  private final JdbcTemplate jdbc;

  public CareSpaceAccessRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public CareSpaceAccess lockCurrentAccess(UUID userId, UUID spaceId) {
    // Always lock the space before its membership. Other mutations must use the same order.
    var space =
        jdbc
            .query(
                """
        SELECT owner_membership_id, version FROM care_space
        WHERE id = ? AND lifecycle = 'ACTIVE' FOR UPDATE
        """,
                (rs, row) ->
                    new Owner(
                        rs.getObject("owner_membership_id", UUID.class), rs.getLong("version")),
                spaceId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new ApiException(CommonErrorCode.RESOURCE_NOT_FOUND));
    UUID membership =
        jdbc
            .query(
                """
        SELECT id FROM space_membership WHERE space_id = ? AND user_id = ? AND is_active FOR UPDATE
        """,
                (rs, row) -> rs.getObject("id", UUID.class),
                spaceId,
                userId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new ApiException(CommonErrorCode.FORBIDDEN));
    return new CareSpaceAccess(
        spaceId,
        membership,
        membership.equals(space.id()) ? CareSpaceRole.OWNER : CareSpaceRole.MEMBER,
        space.version());
  }

  private record Owner(UUID id, long version) {}
}
