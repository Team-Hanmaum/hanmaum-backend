package com.hanmaum.backend.carespace.repository;

import com.hanmaum.backend.carespace.code.CareSpaceErrorCode;
import com.hanmaum.backend.carespace.entity.Invitation;
import com.hanmaum.backend.global.exception.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class InvitationRepository {
  private final JdbcTemplate jdbc;

  public InvitationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Invitation> findByHash(String hash) {
    return jdbc.query("SELECT * FROM invitation WHERE token_hash = ?", this::map, hash).stream()
        .findFirst();
  }

  public Optional<Invitation> findById(UUID id) {
    return jdbc.query("SELECT * FROM invitation WHERE id = ?", this::map, id).stream().findFirst();
  }

  public void insert(Invitation invitation) {
    jdbc.update(
        """
        INSERT INTO invitation
          (id, space_id, issued_by_membership_id, token_hash, encrypted_url, issued_at, expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        invitation.id(),
        invitation.spaceId(),
        invitation.issuedByMembershipId(),
        invitation.tokenHash(),
        invitation.encryptedUrl(),
        Timestamp.from(invitation.issuedAt()),
        Timestamp.from(invitation.expiresAt()));
  }

  /** Same lock order as CareSpaceAccessService: space first, then participation rows. */
  public UUID lockActiveSpace(UUID spaceId) {
    return jdbc
        .query(
            "SELECT owner_membership_id FROM care_space WHERE id = ? AND lifecycle = 'ACTIVE' FOR UPDATE",
            (rs, row) -> rs.getObject("owner_membership_id", UUID.class),
            spaceId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(CareSpaceErrorCode.INVITATION_UNAVAILABLE));
  }

  public void requireCurrentIssuer(Invitation invitation) {
    var issuer =
        jdbc.query(
            """
        SELECT id FROM space_membership
        WHERE space_id = ? AND id = ? AND is_active FOR UPDATE
        """,
            (rs, row) -> rs.getObject("id", UUID.class),
            invitation.spaceId(),
            invitation.issuedByMembershipId());
    if (issuer.isEmpty()) throw new ApiException(CareSpaceErrorCode.INVITATION_UNAVAILABLE);
  }

  public Optional<Participation> findCurrent(UUID userId, UUID spaceId) {
    return jdbc
        .query(
            """
        SELECT id, joined_at FROM space_membership
        WHERE space_id = ? AND user_id = ? AND is_active FOR UPDATE
        """,
            (rs, row) ->
                new Participation(
                    rs.getObject("id", UUID.class), rs.getTimestamp("joined_at").toInstant()),
            spaceId,
            userId)
        .stream()
        .findFirst();
  }

  public Optional<Instant> lastEndedAt(UUID userId, UUID spaceId) {
    Timestamp ended =
        jdbc.queryForObject(
            """
        SELECT max(ended_at) FROM space_membership WHERE space_id = ? AND user_id = ?
        """,
            Timestamp.class,
            spaceId,
            userId);
    return Optional.ofNullable(ended).map(Timestamp::toInstant);
  }

  public Participation join(UUID userId, Invitation invitation, Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO space_membership (id, space_id, user_id, joined_at, invitation_id)
        VALUES (?, ?, ?, ?, ?)
        """,
        id,
        invitation.spaceId(),
        userId,
        Timestamp.from(now),
        invitation.id());
    jdbc.update(
        "UPDATE care_space SET version = version + 1, updated_at = ? WHERE id = ?",
        Timestamp.from(now),
        invitation.spaceId());
    return new Participation(id, now);
  }

  private Invitation map(ResultSet rs, int row) throws SQLException {
    return new Invitation(
        rs.getObject("id", UUID.class),
        rs.getObject("space_id", UUID.class),
        rs.getObject("issued_by_membership_id", UUID.class),
        rs.getString("token_hash"),
        rs.getString("encrypted_url"),
        rs.getTimestamp("issued_at").toInstant(),
        rs.getTimestamp("expires_at").toInstant());
  }

  public record Participation(UUID id, Instant joinedAt) {}
}
