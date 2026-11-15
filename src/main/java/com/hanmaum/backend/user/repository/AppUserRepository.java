package com.hanmaum.backend.user.repository;

import com.hanmaum.backend.user.entity.AppUser;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
  // Advance the request time even when the URL is unchanged, without treating it as a profile edit.
  @Modifying
  @Query(
      value =
          """
      UPDATE app_user
      SET updated_at = CASE WHEN profile_image_url IS DISTINCT FROM :imageUrl
                          THEN clock_timestamp() ELSE updated_at END,
          profile_image_url = :imageUrl,
          profile_image_request_started_at = :requestStartedAt
      WHERE id = :id AND (profile_image_request_started_at IS NULL
                          OR profile_image_request_started_at < :requestStartedAt)
      """,
      nativeQuery = true)
  void updateProfileImageIfNewer(UUID id, String imageUrl, Instant requestStartedAt);
}
