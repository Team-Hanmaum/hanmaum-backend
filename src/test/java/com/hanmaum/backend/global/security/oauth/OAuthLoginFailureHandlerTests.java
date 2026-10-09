package com.hanmaum.backend.global.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class OAuthLoginFailureHandlerTests {
  private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
  private final OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler(mapper);

  @Test
  void logsMissingAuthorizationRequestWithoutExposingAuthenticationData(CapturedOutput output)
      throws Exception {
    var request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
    request.addParameter("state", "PRIVATE-STATE");
    request.addParameter("code", "PRIVATE-CODE");
    var response = new MockHttpServletResponse();
    var error =
        new OAuth2Error("authorization_request_not_found", "PRIVATE-DESCRIPTION", "PRIVATE-URI");
    handler.onAuthenticationFailure(
        request,
        response,
        new OAuth2AuthenticationException(
            error, "PRIVATE-MESSAGE", new RuntimeException("PRIVATE-CAUSE")));

    assertThat(output.getAll())
        .contains(
            "provider=google",
            "reason=AUTHORIZATION_REQUEST_MISSING",
            "sessionPresent=false",
            "statePresent=true",
            "codePresent=true")
        .doesNotContain("PRIVATE-");
    assertThat(request.getSession(false)).isNull();
    assertThat(response.getStatus()).isEqualTo(401);
    var body = mapper.readTree(response.getContentAsString());
    assertThat(body.size()).isEqualTo(6);
    assertThat(body.get("code").asText()).isEqualTo("OAUTH_LOGIN_FAILED");
    assertThat(body.get("message").asText()).isEqualTo("소셜 로그인에 실패했습니다.");
    assertThat(response.getContentAsString())
        .doesNotContain("PRIVATE-", "AUTHORIZATION_REQUEST_MISSING");
  }

  @Test
  void neverLogsUnrecognizedErrorCodesOrRequestPaths(CapturedOutput output) throws Exception {
    var request = new MockHttpServletRequest("GET", "/login/oauth2/code/PRIVATE-PATH");
    var response = new MockHttpServletResponse();
    handler.onAuthenticationFailure(
        request,
        response,
        new OAuth2AuthenticationException(new OAuth2Error("PRIVATE-CODE\nforged-log")));
    assertThat(output.getAll())
        .contains("provider=unknown", "reason=UNKNOWN")
        .doesNotContain("PRIVATE-", "forged-log");
  }

  @Test
  void keepsInternalMemberFailuresOutOfThePublicResponse(CapturedOutput output) throws Exception {
    var request = new MockHttpServletRequest("GET", "/login/oauth2/code/kakao");
    var response = new MockHttpServletResponse();
    handler.onAuthenticationFailure(
        request, response, OAuthMemberMapper.loginFailed("hanmaum_member_mapping_failed"));
    assertThat(output.getAll()).contains("provider=kakao", "reason=MEMBER_MAPPING_FAILED");
    assertThat(mapper.readTree(response.getContentAsString()).get("code").asText())
        .isEqualTo("OAUTH_LOGIN_FAILED");
    assertThat(response.getContentAsString()).doesNotContain("hanmaum_member_mapping_failed");
  }
}
