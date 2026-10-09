package com.hanmaum.backend.global.security.oauth;

import com.hanmaum.backend.user.entity.SocialProvider;
import java.io.Serial;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public final class MemberOidcUser extends DefaultOidcUser implements MemberPrincipal {
  @Serial private static final long serialVersionUID = 1L;
  private final UUID userId;

  public MemberOidcUser(UUID userId, OidcUser verifiedUser) {
    super(
        verifiedUser.getAuthorities(),
        verifiedUser.getIdToken(),
        verifiedUser.getUserInfo(),
        "sub");
    this.userId = Objects.requireNonNull(userId);
  }

  @Override
  public UUID getUserId() {
    return userId;
  }

  @Override
  public SocialProvider getProvider() {
    return SocialProvider.GOOGLE;
  }

  @Override
  public String getProviderUserId() {
    return getIdToken().getSubject();
  }

  @Override
  public String getName() {
    return userId.toString();
  }
}
