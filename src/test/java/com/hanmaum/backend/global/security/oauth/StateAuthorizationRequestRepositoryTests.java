package com.hanmaum.backend.global.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

class StateAuthorizationRequestRepositoryTests {
  private final StateAuthorizationRequestRepository repository =
      new StateAuthorizationRequestRepository();
  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final URI production = URI.create("https://hanmaum.site/auth/callback");

  @Test
  void failedStartRemovesOnlyTheRequestItJustSaved() {
    var session = new MockHttpSession();
    var first = request("/oauth2/authorization/google", session);
    repository.saveAuthorizationRequest(authorization("first"), first, response);
    var second = request("/oauth2/authorization/kakao", session);
    repository.saveAuthorizationRequest(authorization("second"), second, response);
    repository.clear(second, response);
    assertThat(session.getAttribute(StateAuthorizationRequestRepository.ATTRIBUTE_PREFIX + "first"))
        .isNotNull();
    assertThat(
            session.getAttribute(StateAuthorizationRequestRepository.ATTRIBUTE_PREFIX + "second"))
        .isNull();
    assertThat(Collections.list(second.getAttributeNames())).isEmpty();
  }

  @Test
  void resolvedDestinationIsConsumedAndCallbackCannotOverrideIt() {
    var session = new MockHttpSession();
    repository.saveAuthorizationRequest(
        authorization("state"), request("/oauth2/authorization/kakao", session), response);
    var callback = request("/login/oauth2/code/kakao", session);
    callback.setParameter("state", "state");
    callback.setParameter("frontend", "https://evil.invalid");
    assertThat(repository.removeAuthorizationRequest(callback, response)).isNotNull();
    assertThat(Collections.list(session.getAttributeNames())).isEmpty();
    assertThat(repository.takeSuccessUrl(callback, production))
        .isEqualTo(FrontendAuthorizationRequestResolver.LOCAL_SUCCESS_URL);
    assertThat(Collections.list(callback.getAttributeNames())).isEmpty();
    assertThat(repository.takeSuccessUrl(callback, production)).isEqualTo(production.toString());
    assertThat(repository.removeAuthorizationRequest(callback, response)).isNull();
  }

  @Test
  void failureAfterRemovalClearsRequestScopedDestination() {
    var session = new MockHttpSession();
    repository.saveAuthorizationRequest(
        authorization("state"), request("/oauth2/authorization/kakao", session), response);
    var callback = request("/login/oauth2/code/kakao", session);
    callback.setParameter("state", "state");
    repository.removeAuthorizationRequest(callback, response);
    repository.clear(callback, response);
    assertThat(Collections.list(callback.getAttributeNames())).isEmpty();
    assertThat(Collections.list(session.getAttributeNames())).isEmpty();
  }

  @Test
  void ambiguousStateDoesNotConsumeEitherRequest() {
    var session = new MockHttpSession();
    var start = request("/oauth2/authorization/kakao", session);
    repository.saveAuthorizationRequest(authorization("first"), start, response);
    repository.saveAuthorizationRequest(authorization("second"), start, response);
    var callback = request("/login/oauth2/code/kakao", session);
    callback.setParameter("state", "first", "second");
    assertThat(repository.removeAuthorizationRequest(callback, response)).isNull();
    repository.clear(callback, response);
    assertThat(Collections.list(session.getAttributeNames())).hasSize(2);
  }

  @Test
  void authorizationStartedBeforeDeploymentCanStillFinish() {
    var session = new MockHttpSession();
    var old =
        OAuth2AuthorizationRequest.from(authorization("old"))
            .attributes(
                attributes ->
                    attributes.remove(FrontendAuthorizationRequestResolver.SUCCESS_URL_ATTRIBUTE))
            .build();
    new HttpSessionOAuth2AuthorizationRequestRepository()
        .saveAuthorizationRequest(old, request("/oauth2/authorization/kakao", session), response);
    var callback = request("/login/oauth2/code/kakao", session);
    callback.setParameter("state", "old");
    assertThat(repository.removeAuthorizationRequest(callback, response)).isEqualTo(old);
    assertThat(repository.takeSuccessUrl(callback, production)).isEqualTo(production.toString());
    assertThat(Collections.list(session.getAttributeNames())).isEmpty();
  }

  private OAuth2AuthorizationRequest authorization(String state) {
    return OAuth2AuthorizationRequest.authorizationCode()
        .authorizationUri("https://provider.invalid/authorize")
        .clientId("synthetic-client")
        .redirectUri("https://api.hanmaum.site/login/oauth2/code/kakao")
        .state(state)
        .attributes(
            attributes ->
                attributes.put(
                    FrontendAuthorizationRequestResolver.SUCCESS_URL_ATTRIBUTE,
                    FrontendAuthorizationRequestResolver.LOCAL_SUCCESS_URL))
        .build();
  }

  private MockHttpServletRequest request(String path, MockHttpSession session) {
    var request = new MockHttpServletRequest("GET", path);
    request.setSession(session);
    return request;
  }
}
