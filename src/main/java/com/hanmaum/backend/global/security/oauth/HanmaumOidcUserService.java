package com.hanmaum.backend.global.security.oauth;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class HanmaumOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
  private final OAuthMemberMapper members;
  private final Clock clock;
  private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;

  @Autowired
  public HanmaumOidcUserService(OAuthMemberMapper members, Clock clock) {
    this(members, clock, new OidcUserService());
  }

  HanmaumOidcUserService(
      OAuthMemberMapper members,
      Clock clock,
      OAuth2UserService<OidcUserRequest, OidcUser> delegate) {
    this.members = members;
    this.clock = clock;
    this.delegate = delegate;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest request) {
    if (!"google".equals(request.getClientRegistration().getRegistrationId())) {
      throw OAuthMemberMapper.loginFailed("hanmaum_unsupported_provider");
    }
    var profileRequestStartedAt = clock.instant();
    OidcUser user = delegate.loadUser(request);
    return new MemberOidcUser(members.mapGoogle(user, profileRequestStartedAt), user);
  }
}
