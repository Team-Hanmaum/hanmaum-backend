package com.hanmaum.backend.global.security.oauth;

import com.hanmaum.backend.auth.code.AuthErrorCode;
import com.hanmaum.backend.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {
  private static final Logger log = LoggerFactory.getLogger(OAuthLoginFailureHandler.class);
  private final ObjectMapper mapper;

  public OAuthLoginFailureHandler(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    String provider =
        switch (request.getRequestURI()) {
          case "/login/oauth2/code/google" -> "google";
          case "/login/oauth2/code/kakao" -> "kakao";
          default -> "unknown";
        };
    // Only allowlisted categories and booleans: never log URLs, parameters or exception messages.
    log.warn(
        "OAuth login failed: provider={}, reason={}, sessionPresent={}, statePresent={}, codePresent={}",
        provider,
        reason(exception),
        request.getSession(false) != null,
        request.getParameter("state") != null,
        request.getParameter("code") != null);
    var error = AuthErrorCode.OAUTH_LOGIN_FAILED;
    response.setStatus(error.status().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    mapper.writeValue(response.getOutputStream(), ApiResponse.error(error));
  }

  private static String reason(AuthenticationException exception) {
    if (!(exception instanceof OAuth2AuthenticationException oauth)) return "UNKNOWN";
    return switch (oauth.getError().getErrorCode()) {
      case "authorization_request_not_found" -> "AUTHORIZATION_REQUEST_MISSING";
      case "invalid_state_parameter" -> "STATE_MISMATCH";
      case "invalid_nonce" -> "NONCE_MISMATCH";
      case "invalid_id_token" -> "ID_TOKEN_INVALID";
      case "invalid_token_response" -> "TOKEN_RESPONSE_INVALID";
      case "invalid_user_info_response", "invalid_user_info_response_subject" ->
          "USER_INFO_INVALID";
      case "invalid_client", "unauthorized_client" -> "CLIENT_REJECTED";
      case "invalid_grant" -> "GRANT_REJECTED";
      case "access_denied" -> "PROVIDER_DENIED";
      case "invalid_request" -> "REQUEST_INVALID";
      case "hanmaum_member_mapping_failed" -> "MEMBER_MAPPING_FAILED";
      case "hanmaum_invalid_provider_identity" -> "PROVIDER_IDENTITY_INVALID";
      case "hanmaum_unsupported_provider" -> "PROVIDER_UNSUPPORTED";
      default -> "UNKNOWN";
    };
  }
}
