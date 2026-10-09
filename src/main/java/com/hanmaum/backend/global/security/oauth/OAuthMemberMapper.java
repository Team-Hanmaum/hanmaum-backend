package com.hanmaum.backend.global.security.oauth;

import com.hanmaum.backend.auth.code.AuthErrorCode;
import com.hanmaum.backend.user.entity.SocialProvider;
import com.hanmaum.backend.user.service.SocialLoginMemberService;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

@Component
public class OAuthMemberMapper {
  private final SocialLoginMemberService members;

  public OAuthMemberMapper(SocialLoginMemberService members) {
    this.members = members;
  }

  public void mapGoogle(OidcUser user) {
    Object subject = user.getIdToken().getClaims().get("sub");
    if (!(subject instanceof String providerUserId) || providerUserId.isBlank()) {
      throw loginFailed("hanmaum_invalid_provider_identity");
    }
    map(SocialProvider.GOOGLE, providerUserId, optionalName(user.getClaims().get("name")));
  }

  public void mapKakao(OAuth2User user) {
    Object id = user.getAttributes().get("id");
    if (!(id instanceof Long || id instanceof Integer) || ((Number) id).longValue() <= 0) {
      throw loginFailed("hanmaum_invalid_provider_identity");
    }
    String name = null;
    if (user.getAttributes().get("kakao_account") instanceof Map<?, ?> account
        && account.get("profile") instanceof Map<?, ?> profile) {
      name = optionalName(profile.get("nickname"));
    }
    map(SocialProvider.KAKAO, id.toString(), name);
  }

  private void map(SocialProvider provider, String providerUserId, String name) {
    try {
      // Provider HTTP calls have already finished before this short database transaction starts.
      members.findOrCreate(provider, providerUserId, name);
    } catch (DataAccessException | TransactionException exception) {
      // Do not carry SQL parameters or provider data into authentication errors/logs.
      throw loginFailed("hanmaum_member_mapping_failed");
    }
  }

  private static String optionalName(Object value) {
    return value instanceof String name && !name.isBlank() ? name : null;
  }

  static OAuth2AuthenticationException loginFailed(String internalCode) {
    return new OAuth2AuthenticationException(
        new OAuth2Error(internalCode), AuthErrorCode.OAUTH_LOGIN_FAILED.message());
  }
}
