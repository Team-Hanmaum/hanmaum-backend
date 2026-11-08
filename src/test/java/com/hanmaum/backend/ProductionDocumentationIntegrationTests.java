package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanmaum.backend.global.security.SecurityProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "DB_URL=jdbc:postgresql://unused.invalid/unused",
      "DB_USERNAME=unused",
      "DB_PASSWORD=synthetic-unused",
      "FRONTEND_URL=https://hanmaum.site",
      "LOGIN_SUCCESS_URL=https://hanmaum.site/auth/callback",
      "INVITATION_URL_PREFIX=https://hanmaum.site/invitations/",
      "INVITATION_ENCRYPTION_KEY=dGVzdC1vbmx5LWludml0YXRpb24ta2V5LTMyYnl0ZXM=",
      "COMMAND_DIGEST_KEY=synthetic-production-test-key-at-least-32-bytes",
      "AI_BASE_URL=https://ai.invalid",
      "AI_INTERNAL_API_KEY=synthetic-internal-api-key",
      "app.security.allowed-origins[0]=https://hanmaum.site",
      "GOOGLE_CLIENT_ID=synthetic-google-client",
      "GOOGLE_CLIENT_SECRET=synthetic-google-secret",
      "KAKAO_CLIENT_ID=synthetic-kakao-client",
      "KAKAO_CLIENT_SECRET=synthetic-kakao-secret"
    })
@AutoConfigureMockMvc
@ActiveProfiles({"prod", "oauth"})
@Import(TestcontainersConfiguration.class)
class ProductionDocumentationIntegrationTests {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired SecurityProperties security;
  @Autowired Environment environment;

  @Test
  void separatesLoginSuccessFromSharedLinkBaseInProduction() {
    assertThat(security.loginSuccessUrl().toString())
        .isEqualTo("https://hanmaum.site/auth/callback");
    assertThat(environment.getProperty("app.sharing.url-prefix"))
        .isEqualTo("https://hanmaum.site/share/");
    assertThat(environment.getProperty("app.invitations.url-prefix"))
        .isEqualTo("https://hanmaum.site/invitations/");
  }

  @ParameterizedTest
  @ValueSource(strings = {"google", "kakao"})
  void keepsProviderCallbackOnBackendBehindProductionProxy(String provider) throws Exception {
    var response =
        mvc.perform(
                get("http://backend:8080/oauth2/authorization/" + provider)
                    .header("X-Forwarded-Host", "api.hanmaum.site")
                    .header("X-Forwarded-Proto", "https")
                    .header("X-Forwarded-Port", "443"))
            .andExpect(status().isFound())
            .andReturn()
            .getResponse();
    var query =
        UriComponentsBuilder.fromUriString(response.getRedirectedUrl()).build().getQueryParams();
    assertThat(UriUtils.decode(query.getFirst("redirect_uri"), StandardCharsets.UTF_8))
        .isEqualTo("https://api.hanmaum.site/login/oauth2/code/" + provider);
  }

  @Test
  void allowsCredentialedFrontendPreflightWithCsrfHeaderOnlyForConfiguredOrigin() throws Exception {
    mvc.perform(
            options("/api/auth/logout")
                .header("Origin", "https://hanmaum.site")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type,X-CSRF-TOKEN"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "https://hanmaum.site"))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
        .andExpect(header().string("Access-Control-Allow-Headers", "Content-Type, X-CSRF-TOKEN"));
    mvc.perform(
            options("/api/auth/logout")
                .header("Origin", "https://untrusted.invalid")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  @Test
  void preservesProductionCookieAndCsrfLogoutContract() throws Exception {
    var response =
        mvc.perform(
                get("https://api.hanmaum.site/api/auth/csrf")
                    .header("Origin", "https://hanmaum.site"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://hanmaum.site"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andReturn()
            .getResponse();
    var cookie = response.getCookie("HANMAUM_SESSION");
    assertThat(cookie).isNotNull();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getSecure()).isTrue();
    assertThat(cookie.getAttribute("SameSite")).isEqualToIgnoringCase("Lax");
    assertThat(cookie.getPath()).isEqualTo("/");
    assertThat(cookie.getDomain()).isNull();
    var csrf = mapper.readTree(response.getContentAsString());
    assertThat(csrf.propertyNames()).containsExactlyInAnyOrder("headerName", "token");
    mvc.perform(
            post("https://api.hanmaum.site/api/auth/logout")
                .header("Origin", "https://hanmaum.site")
                .cookie(cookie)
                .header(csrf.get("headerName").asText(), csrf.get("token").asText()))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
  }

  @ParameterizedTest
  @ValueSource(strings = {"google", "kakao"})
  void keepsOAuthFailureAsJsonWithoutFrontendRedirect(String provider) throws Exception {
    mvc.perform(
            get("/login/oauth2/code/" + provider)
                .param("error", "access_denied")
                .param("state", "missing-authorization-session"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist("Location"))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("OAUTH_LOGIN_FAILED"))
        .andExpect(jsonPath("$.message").value("소셜 로그인에 실패했습니다."));
  }

  /**
   * 오류 응답을 명시한 API에서 추론된 200 응답이 누락되는 회귀를 검증한다. 운영 프로필의 생성 문서를 대상으로 공통 응답과 중첩 DTO, 기존 인증·오류 계약을 함께
   * 확인한다.
   */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          /proposal-applications | ProposalApplicationResponse | batchId,appliedAt,results
          /proposal-declines | ProposalDeclineResponse | batchId,declinedProposalIds,decidedAt
          /request-applications | ChangeRequestApplied | batchId,appliedAt,results
          /request-declines | ChangeRequestDeclined | batchId,decidedAt,declinedRequestEntryIds
          /change-requests/{requestId}/cancellations | ChangeRequestCanceled | batchId,decidedAt,canceledRequestEntryIds
          /items/{itemId}/edit-lock/renewals | EditLockResponse | itemId,leaseToken,holderMembershipId,expiresAt,renewAfterSeconds
          """)
  void documentsSuccessfulMutationEnvelopesAndActualDataSchemas(
      String suffix, String dataSchemaName, String dataFields) throws Exception {
    var document =
        mapper.readTree(
            mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var operation = document.path("paths").path("/api/spaces/{spaceId}" + suffix).path("post");
    var success = operation.at("/responses/200");
    assertThat(success.isMissingNode()).as("200 response for %s", suffix).isFalse();
    assertThat(success.path("description").asText()).isNotBlank();
    var envelopeRef = success.at("/content/application~1json/schema/$ref").asText();
    assertThat(envelopeRef).startsWith("#/components/schemas/");
    var envelope = document.at(envelopeRef.substring(1));
    assertThat(envelope.path("properties").propertyNames())
        .containsExactlyInAnyOrder("success", "code", "message", "data", "errors", "timestamp");
    assertThat(envelope.at("/properties/data/$ref").asText())
        .isEqualTo("#/components/schemas/" + dataSchemaName);
    var dataSchema = document.path("components").path("schemas").path(dataSchemaName);
    assertThat(dataSchema.path("properties").propertyNames())
        .containsExactlyInAnyOrder(dataFields.split(","));
    if (dataSchema.path("properties").has("results")) {
      var itemRef = dataSchema.at("/properties/results/items/$ref").asText();
      assertThat(itemRef).startsWith("#/components/schemas/");
      assertThat(document.at(itemRef.substring(1)).path("properties").propertyNames())
          .containsExactlyInAnyOrder(
              suffix.equals("/proposal-applications")
                  ? new String[] {"proposalId", "itemId", "itemVersion"}
                  : new String[] {"requestEntryId", "itemId", "itemVersion", "spaceVersion"});
    }
    assertThat(operation.path("security").size()).isEqualTo(1);
    assertThat(operation.path("security").get(0).propertyNames())
        .containsExactlyInAnyOrder("SessionCookie", "CsrfToken");
    assertThat(operation.path("responses").propertyNames())
        .contains("400", "401", "403", "404", "409", "500");
  }

  @Test
  void exposesDocumentationAnonymouslyInTheRealProductionProfiles() throws Exception {
    mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/users/me']").exists())
        .andExpect(
            jsonPath("$.components.securitySchemes.SessionCookie.name").value("HANMAUM_SESSION"))
        .andExpect(jsonPath("$.components.securitySchemes.CsrfToken.name").value("X-CSRF-TOKEN"));
  }

  @Test
  void keepsApiAuthenticationAndCsrfWhenDocumentationIsPublic() throws Exception {
    mvc.perform(get("/api/users/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mvc.perform(post("/api/auth/logout").with(user("synthetic-member")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }
}
