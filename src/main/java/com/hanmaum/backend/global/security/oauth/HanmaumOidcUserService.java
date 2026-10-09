package com.hanmaum.backend.global.security.oauth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class HanmaumOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
  private final OAuthMemberMapper members;
  private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;

  @Autowired
  public HanmaumOidcUserService(OAuthMemberMapper members) {
    this(members, new OidcUserService());
  }

  HanmaumOidcUserService(
      OAuthMemberMapper members, OAuth2UserService<OidcUserRequest, OidcUser> delegate) {
    this.members = members;
    this.delegate = delegate;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest request) {
    if (!"google".equals(request.getClientRegistration().getRegistrationId())) {
      throw OAuthMemberMapper.loginFailed("hanmaum_unsupported_provider");
    }
    OidcUser user = delegate.loadUser(request);
    members.mapGoogle(user);
    return user;
  }
}
