package com.hanmaum.backend.user.service;

import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.user.dto.MyProfileResponse;
import com.hanmaum.backend.user.entity.SocialAccount;
import com.hanmaum.backend.user.repository.AppUserRepository;
import com.hanmaum.backend.user.repository.SocialAccountRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {
  private final AppUserRepository users;
  private final SocialAccountRepository socialAccounts;

  public UserProfileService(AppUserRepository users, SocialAccountRepository socialAccounts) {
    this.users = users;
    this.socialAccounts = socialAccounts;
  }

  @Transactional(readOnly = true)
  public MyProfileResponse getMe(UUID userId) {
    // The member can be deleted between argument resolution and this transaction.
    var user =
        users.findById(userId).orElseThrow(() -> new ApiException(CommonErrorCode.UNAUTHENTICATED));
    var providers =
        socialAccounts.findAllByUser_Id(userId).stream()
            .map(SocialAccount::getProvider)
            .distinct()
            .sorted()
            .toList();
    return new MyProfileResponse(user.getId(), user.getDisplayName(), providers);
  }
}
