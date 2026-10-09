package com.hanmaum.backend.global.security.oauth;

import com.hanmaum.backend.user.entity.SocialProvider;
import java.io.Serial;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

public final class MemberOAuth2User extends DefaultOAuth2User implements MemberPrincipal {
  @Serial private static final long serialVersionUID = 1L;
  private final UUID userId;

  public MemberOAuth2User(UUID userId, OAuth2User verifiedUser) {
    super(verifiedUser.getAuthorities(), verifiedUser.getAttributes(), "id");
    this.userId = Objects.requireNonNull(userId);
  }

  @Override
  public UUID getUserId() {
    return userId;
  }

  @Override
  public SocialProvider getProvider() {
    return SocialProvider.KAKAO;
  }

  @Override
  public String getProviderUserId() {
    return getAttributes().get("id").toString();
  }

  @Override
  public String getName() {
    return userId.toString();
  }
}
