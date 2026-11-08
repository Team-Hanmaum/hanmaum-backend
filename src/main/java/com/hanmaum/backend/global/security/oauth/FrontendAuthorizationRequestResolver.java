package com.hanmaum.backend.global.security.oauth;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Selects only server-owned destinations; keeps Spring's state, nonce, PKCE and BE redirect URI.
 */
public final class FrontendAuthorizationRequestResolver
    implements OAuth2AuthorizationRequestResolver {
  static final String SUCCESS_URL_ATTRIBUTE = "hanmaum.loginSuccessUrl";
  public static final String LOCAL_SUCCESS_URL = "https://local.hanmaum.site:5173/auth/callback";
  private final OAuth2AuthorizationRequestResolver delegate;
  private final String defaultSuccessUrl;

  public FrontendAuthorizationRequestResolver(
      ClientRegistrationRepository clients, URI defaultSuccessUrl) {
    this.delegate = new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
    this.defaultSuccessUrl = defaultSuccessUrl.toString();
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
    return selectFrontend(delegate.resolve(request), request);
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
    return selectFrontend(delegate.resolve(request, registrationId), request);
  }

  private OAuth2AuthorizationRequest selectFrontend(
      OAuth2AuthorizationRequest authorization, HttpServletRequest request) {
    if (authorization == null) return null;
    String[] frontend = request.getParameterValues("frontend");
    if (frontend != null && (frontend.length != 1 || !"local".equals(frontend[0]))) {
      throw new OAuth2AuthenticationException(new OAuth2Error("invalid_request"));
    }
    String successUrl = frontend == null ? defaultSuccessUrl : LOCAL_SUCCESS_URL;
    // Attributes remain in the server session, unlike additionalParameters sent to the provider.
    return OAuth2AuthorizationRequest.from(authorization)
        .attributes(attributes -> attributes.put(SUCCESS_URL_ATTRIBUTE, successUrl))
        .build();
  }
}
