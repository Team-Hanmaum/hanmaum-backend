package com.hanmaum.backend.global.security.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.util.Assert;

/** One JDBC session attribute per state avoids overwriting another tab's pending request. */
public final class StateAuthorizationRequestRepository
    implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
  public static final String ATTRIBUTE_PREFIX =
      StateAuthorizationRequestRepository.class.getName() + ".REQUEST.";
  private static final String SAVED_REQUEST = ATTRIBUTE_PREFIX + "saved";
  private static final String SUCCESS_URL = ATTRIBUTE_PREFIX + "successUrl";
  // Allow an authorization already started before this change to finish using its existing state.
  private final HttpSessionOAuth2AuthorizationRequestRepository legacy =
      new HttpSessionOAuth2AuthorizationRequestRepository();

  @Override
  public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
    String[] states = request.getParameterValues("state");
    var session = request.getSession(false);
    if (session == null || states == null || states.length != 1 || states[0].isBlank()) return null;
    Object stored = session.getAttribute(ATTRIBUTE_PREFIX + states[0]);
    if (stored instanceof OAuth2AuthorizationRequest authorization) {
      return states[0].equals(authorization.getState()) ? authorization : null;
    }
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
    // Do not store a mutable map in one attribute: concurrent JDBC snapshots would lose updates.
    request.getSession().setAttribute(ATTRIBUTE_PREFIX + authorization.getState(), authorization);
    request.setAttribute(SAVED_REQUEST, authorization);
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
}
