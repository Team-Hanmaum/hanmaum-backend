package com.hanmaum.backend.user.repository;

import com.hanmaum.backend.user.entity.SocialAccount;
import com.hanmaum.backend.user.entity.SocialProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, UUID> {
  @EntityGraph(attributePaths = "user")
  Optional<SocialAccount> findByProviderAndProviderUserId(
      SocialProvider provider, String providerUserId);

  List<SocialAccount> findAllByUser_Id(UUID userId);
}
