package com.hanmaum.backend.global.security.oauth;

import com.hanmaum.backend.user.entity.SocialProvider;
import java.util.UUID;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Authenticated service identity, created only after successful provider verification and mapping.
 */
public interface MemberPrincipal extends OAuth2User {
  UUID getUserId();

  SocialProvider getProvider();

  String getProviderUserId();
}
