package com.hanmaum.backend.user.service;

import com.hanmaum.backend.user.entity.AppUser;
import com.hanmaum.backend.user.entity.SocialAccount;
import com.hanmaum.backend.user.entity.SocialProvider;
import com.hanmaum.backend.user.repository.AppUserRepository;
import com.hanmaum.backend.user.repository.SocialAccountIdentityLockRepository;
import com.hanmaum.backend.user.repository.SocialAccountRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SocialLoginMemberService {
  private final AppUserRepository users;
  private final SocialAccountRepository socialAccounts;
  private final SocialAccountIdentityLockRepository identityLocks;

  public SocialLoginMemberService(
      AppUserRepository users,
      SocialAccountRepository socialAccounts,
      SocialAccountIdentityLockRepository identityLocks) {
    this.users = users;
    this.socialAccounts = socialAccounts;
    this.identityLocks = identityLocks;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public UUID findOrCreate(
      SocialProvider provider, String providerUserId, String initialDisplayName) {
    Objects.requireNonNull(provider, "provider");
    if (providerUserId == null || providerUserId.isBlank()) {
      throw new IllegalArgumentException("A provider identity is required");
    }

    // Lock before lookup so concurrent first logins cannot leave duplicate or orphan users.
    identityLocks.lock(provider, providerUserId);
    return socialAccounts
        .findByProviderAndProviderUserId(provider, providerUserId)
        .map(account -> account.getUser().getId())
        .orElseGet(
            () -> {
              AppUser user = users.saveAndFlush(new AppUser(initialDisplayName));
              socialAccounts.saveAndFlush(new SocialAccount(user, provider, providerUserId));
              return user.getId();
            });
  }
}
