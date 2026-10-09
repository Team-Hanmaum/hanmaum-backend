package com.hanmaum.backend.user.service;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.user.dto.MyProfileResponse;
import com.hanmaum.backend.user.entity.SocialAccount;
import com.hanmaum.backend.user.entity.SocialProvider;
import com.hanmaum.backend.user.repository.SocialAccountRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {
  private final SocialAccountRepository socialAccounts;

  public UserProfileService(SocialAccountRepository socialAccounts) {
    this.socialAccounts = socialAccounts;
  }

  @Transactional(readOnly = true)
  public MyProfileResponse getMe(UUID userId, SocialProvider provider, String providerUserId) {
    // Recheck the persisted mapping: a session must not revive a deleted or disconnected member.
    var account =
        socialAccounts
            .findByProviderAndProviderUserId(provider, providerUserId)
            .filter(found -> found.getUser().getId().equals(userId))
            .orElseThrow(() -> new ApiException(CommonErrorCode.UNAUTHENTICATED));
    var user = account.getUser();
    var providers =
        socialAccounts.findAllByUser_Id(userId).stream()
            .map(SocialAccount::getProvider)
            .distinct()
            .sorted()
            .toList();
    return new MyProfileResponse(user.getId(), user.getDisplayName(), providers);
  }
}
