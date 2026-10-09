package com.hanmaum.backend.user.service;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.user.entity.SocialProvider;
import com.hanmaum.backend.user.repository.SocialAccountRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberIdentityService {
  private final SocialAccountRepository socialAccounts;

  public MemberIdentityService(SocialAccountRepository socialAccounts) {
    this.socialAccounts = socialAccounts;
  }

  @Transactional(readOnly = true)
  public UUID requireValidUserId(UUID userId, SocialProvider provider, String providerUserId) {
    if (userId == null
        || provider == null
        || providerUserId == null
        || providerUserId.isBlank()
        || !socialAccounts.existsByUser_IdAndProviderAndProviderUserId(
            userId, provider, providerUserId)) {
      throw new ApiException(CommonErrorCode.UNAUTHENTICATED);
    }
    return userId;
  }
}
