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
  private final MemberLifecycleAccess lifecycle;

  public SocialLoginMemberService(
      AppUserRepository users,
      SocialAccountRepository socialAccounts,
      SocialAccountIdentityLockRepository identityLocks,
      MemberLifecycleAccess lifecycle) {
    this.users = users;
    this.socialAccounts = socialAccounts;
    this.identityLocks = identityLocks;
    this.lifecycle = lifecycle;
  }

  /**
   * Resolves a verified provider identity under its registration lock, then checks member
   * lifecycle. Removed mappings create a new account without reconnecting historical memberships.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public UUID findOrCreate(
      SocialProvider provider,
      String providerUserId,
      String initialDisplayName,
      String profileImageUrl) {
    Objects.requireNonNull(provider, "provider");
    if (providerUserId == null || providerUserId.isBlank()) {
      throw new IllegalArgumentException("A provider identity is required");
    }

    // Lock before lookup so concurrent first logins cannot leave duplicate or orphan users.
    identityLocks.lock(provider, providerUserId);
    return socialAccounts
        .findByProviderAndProviderUserId(provider, providerUserId)
        .map(
            account -> {
              AppUser user = account.getUser();
              lifecycle.requireExisting(user.getId());
              user.updateProfileImageUrl(profileImageUrl);
              return user.getId();
            })
        .orElseGet(
            () -> {
              AppUser user = new AppUser(initialDisplayName);
              user.updateProfileImageUrl(profileImageUrl);
              users.saveAndFlush(user);
              socialAccounts.saveAndFlush(new SocialAccount(user, provider, providerUserId));
              return user.getId();
            });
  }
}
