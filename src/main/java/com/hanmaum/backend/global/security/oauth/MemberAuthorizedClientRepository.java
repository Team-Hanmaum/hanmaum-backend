package com.hanmaum.backend.global.security.oauth;

import com.hanmaum.backend.auth.code.AuthErrorCode;
import com.hanmaum.backend.user.service.MemberLifecycleAccess;
import jakarta.servlet.http.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

/** Same in-memory lifetime as the default client service, with account-deletion serialization. */
@Component
public class MemberAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {
  private final MemberLifecycleAccess lifecycle;
  private final Map<Key, OAuth2AuthorizedClient> clients = new ConcurrentHashMap<>();

  public MemberAuthorizedClientRepository(MemberLifecycleAccess lifecycle) {
    this.lifecycle = lifecycle;
  }

  /** A removed account cannot load old provider credentials even through a delayed login. */
  @Override
  @Transactional
  @SuppressWarnings("unchecked")
  public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
      String registrationId, Authentication authentication, HttpServletRequest request) {
    UUID user = user(authentication);
    if (user == null) return null;
    var key = new Key(registrationId, user);
    if (!lifecycle.lockAndExists(user)) {
      clients.remove(key);
      return null;
    }
    return (T) clients.get(key);
  }

  /**
   * Saves under the member lifecycle lock, preventing credentials from returning after withdrawal.
   */
  @Override
  @Transactional
  public void saveAuthorizedClient(
      OAuth2AuthorizedClient client,
      Authentication authentication,
      HttpServletRequest request,
      HttpServletResponse response) {
    UUID user = user(authentication);
    if (user == null || !lifecycle.lockAndExists(user))
      throw new OAuth2AuthenticationException(
          new OAuth2Error("hanmaum_member_removed"), AuthErrorCode.OAUTH_LOGIN_FAILED.message());
    var key = new Key(client.getClientRegistration().getRegistrationId(), user);
    clients.put(key, client);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          /** Removes credentials from a login transaction that did not commit. */
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) clients.remove(key, client);
          }
        });
  }

  /** Removes only the current member's selected provider entry during normal logout cleanup. */
  @Override
  public void removeAuthorizedClient(
      String registrationId,
      Authentication authentication,
      HttpServletRequest request,
      HttpServletResponse response) {
    UUID user = user(authentication);
    if (user != null) clients.remove(new Key(registrationId, user));
  }

  /** Called only after the account-deletion transaction commits. */
  public void removeMember(UUID user) {
    clients.keySet().removeIf(key -> key.user().equals(user));
  }

  /** Provider/anonymous principals cannot index member-owned credentials. */
  private static UUID user(Authentication authentication) {
    return authentication != null
            && authentication.isAuthenticated()
            && authentication.getPrincipal() instanceof MemberPrincipal principal
        ? principal.getUserId()
        : null;
  }

  private record Key(String registrationId, UUID user) {}
}
