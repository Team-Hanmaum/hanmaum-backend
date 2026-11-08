package com.hanmaum.backend.global.security.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.util.Assert;

/** One JDBC session attribute per state avoids overwriting another tab's pending request. */
public final class StateAuthorizationRequestRepository
    implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
  public static final String ATTRIBUTE_PREFIX =
      StateAuthorizationRequestRepository.class.getName() + ".REQUEST.";
  public static final int MAX_PENDING_REQUESTS = 10;
  public static final Duration REQUEST_LIFETIME = Duration.ofMinutes(10);
  private static final String CREATED_AT = "hanmaum.authorizationCreatedAt";
  private static final String LEGACY_ATTRIBUTE =
      HttpSessionOAuth2AuthorizationRequestRepository.class.getName() + ".AUTHORIZATION_REQUEST";
  private static final String SAVED_REQUEST = ATTRIBUTE_PREFIX + "saved";
  private static final String SUCCESS_URL = ATTRIBUTE_PREFIX + "successUrl";
  // Allow an authorization already started before this change to finish using its existing state.
  private final HttpSessionOAuth2AuthorizationRequestRepository legacy =
      new HttpSessionOAuth2AuthorizationRequestRepository();
  private final Clock clock;

  public StateAuthorizationRequestRepository(Clock clock) {
    this.clock = clock;
  }

  @Override
  public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
    String[] states = request.getParameterValues("state");
    var session = request.getSession(false);
    if (session == null || states == null || states.length != 1 || states[0].isBlank()) return null;
    expirePendingRequests(session);
    Object stored = session.getAttribute(ATTRIBUTE_PREFIX + states[0]);
    if (stored instanceof OAuth2AuthorizationRequest authorization) {
      return states[0].equals(authorization.getState()) ? authorization : null;
    }
    // Spring's legacy repository itself returns null unless the callback state matches.
    return legacy.loadAuthorizationRequest(request);
  }

  @Override
  public void saveAuthorizationRequest(
      OAuth2AuthorizationRequest authorization,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (authorization == null) {
      removeAuthorizationRequest(request, response);
      return;
    }
    Assert.hasText(authorization.getState(), "authorizationRequest.state cannot be empty");
    var session = request.getSession();
    expirePendingRequests(session);
    String name = ATTRIBUTE_PREFIX + authorization.getState();
    var pending = pendingNames(session);
    if (!pending.contains(name) && pending.size() >= MAX_PENDING_REQUESTS) {
      throw new OAuth2AuthenticationException(new OAuth2Error("hanmaum_oauth_capacity_exceeded"));
    }
    var timed =
        OAuth2AuthorizationRequest.from(authorization)
            .attributes(attributes -> attributes.put(CREATED_AT, clock.millis()))
            .build();
    // Do not store a mutable map in one attribute: concurrent JDBC snapshots would lose updates.
    session.setAttribute(name, timed);
    request.setAttribute(SAVED_REQUEST, timed);
  }

  @Override
  public OAuth2AuthorizationRequest removeAuthorizationRequest(
      HttpServletRequest request, HttpServletResponse response) {
    var authorization = loadAuthorizationRequest(request);
    if (authorization != null) {
      removeStored(authorization, request, response);
      request.setAttribute(
          SUCCESS_URL,
          authorization.getAttribute(FrontendAuthorizationRequestResolver.SUCCESS_URL_ATTRIBUTE));
    }
    return authorization;
  }

  public String takeSuccessUrl(HttpServletRequest request, URI defaultSuccessUrl) {
    var selected = (String) request.getAttribute(SUCCESS_URL);
    request.removeAttribute(SUCCESS_URL);
    request.removeAttribute(SAVED_REQUEST);
    return selected == null ? defaultSuccessUrl.toString() : selected;
  }

  public void clear(HttpServletRequest request, HttpServletResponse response) {
    // Handles both a failed provider callback and failure while redirecting after saving a start.
    if (request.getAttribute(SAVED_REQUEST) instanceof OAuth2AuthorizationRequest saved) {
      removeStored(saved, request, response);
    } else if (request
        .getRequestURI()
        .startsWith(request.getContextPath() + "/login/oauth2/code/")) {
      removeAuthorizationRequest(request, response);
    }
    request.removeAttribute(SUCCESS_URL);
    request.removeAttribute(SAVED_REQUEST);
  }

  private void removeStored(
      OAuth2AuthorizationRequest authorization,
      HttpServletRequest request,
      HttpServletResponse response) {
    var session = request.getSession(false);
    if (session != null) session.removeAttribute(ATTRIBUTE_PREFIX + authorization.getState());
    var previous = legacy.loadAuthorizationRequest(request);
    if (previous != null && previous.getState().equals(authorization.getState())) {
      legacy.removeAuthorizationRequest(request, response);
    }
  }

  private List<String> pendingNames(HttpSession session) {
    return Collections.list(session.getAttributeNames()).stream()
        .filter(name -> name.startsWith(ATTRIBUTE_PREFIX) || name.equals(LEGACY_ATTRIBUTE))
        .filter(name -> session.getAttribute(name) instanceof OAuth2AuthorizationRequest)
        .toList();
  }

  private void expirePendingRequests(HttpSession session) {
    long now = clock.millis();
    for (String name : pendingNames(session)) {
      var pending = (OAuth2AuthorizationRequest) session.getAttribute(name);
      Object createdAt = pending.getAttribute(CREATED_AT);
      if (!(createdAt instanceof Long created)) {
        // Persist a one-time grace period for requests begun before timestamps were introduced.
        session.setAttribute(
            name,
            OAuth2AuthorizationRequest.from(pending)
                .attributes(attributes -> attributes.put(CREATED_AT, now))
                .build());
      } else if (now - created >= REQUEST_LIFETIME.toMillis()) {
        session.removeAttribute(name);
      }
    }
  }
}
