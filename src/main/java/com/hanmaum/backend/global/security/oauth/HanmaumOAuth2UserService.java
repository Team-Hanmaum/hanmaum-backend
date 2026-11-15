package com.hanmaum.backend.global.security.oauth;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
public class HanmaumOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
  private final OAuthMemberMapper members;
  private final Clock clock;
  private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;

  @Autowired
  public HanmaumOAuth2UserService(OAuthMemberMapper members, Clock clock) {
    this(members, clock, new DefaultOAuth2UserService());
  }

  HanmaumOAuth2UserService(
      OAuthMemberMapper members,
      Clock clock,
      OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate) {
    this.members = members;
    this.clock = clock;
    this.delegate = delegate;
  }

  @Override
  public OAuth2User loadUser(OAuth2UserRequest request) {
    if (!"kakao".equals(request.getClientRegistration().getRegistrationId())) {
      throw OAuthMemberMapper.loginFailed("hanmaum_unsupported_provider");
    }
    var profileRequestStartedAt = clock.instant();
    OAuth2User user = delegate.loadUser(request);
    return new MemberOAuth2User(members.mapKakao(user, profileRequestStartedAt), user);
  }
}
