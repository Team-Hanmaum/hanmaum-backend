package com.hanmaum.backend.user.repository;

import com.hanmaum.backend.user.entity.SocialProvider;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class MemberWithdrawalRepository {
  private final JdbcTemplate jdbc;

  public MemberWithdrawalRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Immutable provider identities are locked in the same order as registration before lookup. */
  public List<Identity> identities(UUID user) {
    return jdbc.query(
        "SELECT provider,provider_user_id FROM social_account WHERE user_id=? ORDER BY provider,provider_user_id COLLATE \"C\"",
        (rs, n) -> new Identity(SocialProvider.valueOf(rs.getString(1)), rs.getString(2)),
        user);
  }

  /**
   * Account lifecycle lock prevents new spaces/joins while all historical space locks are acquired.
   */
  public void lockSpaces(UUID user) {
    jdbc.query(
        "SELECT id FROM care_space WHERE owner_user_id=? OR id IN (SELECT space_id FROM space_membership WHERE user_id=?) ORDER BY id FOR UPDATE",
        rs -> {},
        user,
        user);
  }

  public boolean ownsSpace(UUID user) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM care_space WHERE owner_user_id=?)", Boolean.class, user));
  }

  public List<Membership> activeMemberships(UUID user) {
    return jdbc.query(
        "SELECT space_id,id FROM space_membership WHERE user_id=? AND is_active ORDER BY space_id,id",
        (rs, n) -> new Membership(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
        user);
  }

  /**
   * Preserves the membership IDs used by shared records while removing their account connection.
   */
  public void eraseAccount(UUID user) {
    jdbc.update("UPDATE space_membership SET user_id=NULL WHERE user_id=?", user);
    // These global actor receipts contain only IDs/hashes. FK SET NULL preserves shared records.
    jdbc.update("DELETE FROM command_receipt WHERE actor_user_id=?", user);
    // Social mappings and every indexed member session/attribute cascade in this transaction.
    if (jdbc.update("DELETE FROM app_user WHERE id=?", user) != 1)
      throw new IllegalStateException("Locked member was not removed");
  }

  public record Identity(SocialProvider provider, String providerUserId) {
    @Override
    public String toString() {
      return "Identity[provider=" + provider + "]";
    }
  }

  public record Membership(UUID space, UUID id) {}
}
