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

import com.hanmaum.backend.careitem.dto.CareItemEvidenceResponse;
import com.hanmaum.backend.careitem.dto.CareItemHistoryResponse;
import com.hanmaum.backend.carespace.dto.CareSpaceDeletionImpact;
import com.hanmaum.backend.global.security.SecurityProperties;
import com.hanmaum.backend.proposal.dto.ProposalDetail;
import com.hanmaum.backend.record.dto.SourceRecordAuthor;
import com.hanmaum.backend.record.dto.SourceRecordDeletionImpact;
import com.hanmaum.backend.sharing.dto.SharedItemsResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
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
      "app.security.allowed-origins[1]=https://local.hanmaum.site:5173",
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
  @CsvSource({"google,default", "google,local", "kakao,default", "kakao,local"})
  void keepsProviderCallbackOnBackendBehindProductionProxy(String provider, String frontend)
      throws Exception {
    var start =
        get("http://backend:8080/oauth2/authorization/" + provider)
            .header("X-Forwarded-Host", "api.hanmaum.site")
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Port", "443");
    if (frontend.equals("local")) start.param("frontend", "local");
    var response = mvc.perform(start).andExpect(status().isFound()).andReturn().getResponse();
    var query =
        UriComponentsBuilder.fromUriString(response.getRedirectedUrl()).build().getQueryParams();
    assertThat(UriUtils.decode(query.getFirst("redirect_uri"), StandardCharsets.UTF_8))
        .isEqualTo("https://api.hanmaum.site/login/oauth2/code/" + provider);
    assertThat(query).doesNotContainKeys("frontend", "hanmaum.loginSuccessUrl");
    assertThat(response.getRedirectedUrl()).doesNotContain("local.hanmaum.site");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "production", "LOCAL", "local ", "https://evil.invalid", "//evil.invalid"})
  void rejectsUnknownFrontendSelectorsBeforeProviderRedirect(String frontend) throws Exception {
    for (String provider : List.of("google", "kakao")) {
      mvc.perform(get("/oauth2/authorization/" + provider).param("frontend", frontend))
          .andExpect(status().isUnauthorized())
          .andExpect(header().doesNotExist("Location"))
          .andExpect(jsonPath("$.code").value("OAUTH_LOGIN_FAILED"));
    }
  }

  @Test
  void rejectsRepeatedFrontendSelectors() throws Exception {
    mvc.perform(get("/oauth2/authorization/google").param("frontend", "local", "local"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist("Location"))
        .andExpect(jsonPath("$.code").value("OAUTH_LOGIN_FAILED"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://hanmaum.site", "https://local.hanmaum.site:5173"})
  void allowsCredentialedFrontendPreflightWithCsrfHeader(String origin) throws Exception {
    for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
      mvc.perform(
              options("/api/auth/logout")
                  .header("Origin", origin)
                  .header("Access-Control-Request-Method", method)
                  .header("Access-Control-Request-Headers", "Content-Type,X-CSRF-TOKEN"))
          .andExpect(status().isOk())
          .andExpect(header().string("Access-Control-Allow-Origin", origin))
          .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
          .andExpect(header().string("Access-Control-Allow-Headers", "Content-Type, X-CSRF-TOKEN"));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://untrusted.invalid", "http://local.hanmaum.site:5173",
        "https://local.hanmaum.site:5174", "https://hanmaum.site.evil.invalid"
      })
  void rejectsUnconfiguredFrontendOrigin(String origin) throws Exception {
    mvc.perform(
            options("/api/auth/logout")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://hanmaum.site", "https://local.hanmaum.site:5173"})
  void preservesProductionCookieAndCsrfLogoutContract(String origin) throws Exception {
    var response =
        mvc.perform(get("https://api.hanmaum.site/api/auth/csrf").header("Origin", origin))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", origin))
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
                .header("Origin", origin)
                .cookie(cookie)
                .header(csrf.get("headerName").asText(), csrf.get("token").asText()))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
  }

  @ParameterizedTest
  @ValueSource(strings = {"google", "kakao"})
  @ExtendWith(OutputCaptureExtension.class)
  void keepsProviderDenialAsJsonWithoutFrontendRedirect(String provider, CapturedOutput output)
      throws Exception {
    var start =
        mvc.perform(get("https://api.hanmaum.site/oauth2/authorization/" + provider))
            .andExpect(status().isFound())
            .andReturn()
            .getResponse();
    var cookie = start.getCookie("HANMAUM_SESSION");
    assertThat(cookie).isNotNull();
    var state =
        UriComponentsBuilder.fromUriString(start.getRedirectedUrl())
            .build()
            .getQueryParams()
            .getFirst("state");
    assertThat(state).isNotBlank();
    mvc.perform(
            get("https://api.hanmaum.site/login/oauth2/code/" + provider)
                .cookie(cookie)
                .param("error", "access_denied")
                .param("state", UriUtils.decode(state, StandardCharsets.UTF_8)))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist("Location"))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("OAUTH_LOGIN_FAILED"))
        .andExpect(jsonPath("$.message").value("소셜 로그인에 실패했습니다."));
    // A missing authorization session also returns 401; verify the intended failure path.
    assertThat(output.getAll()).contains("provider=" + provider + ", reason=PROVIDER_DENIED,");
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

  @ParameterizedTest
  @MethodSource("nestedResponseContracts")
  void documentsDistinctNestedDtosWithTheirUnchangedSerializedFields(
      String path, String propertyPath, Object responseValue, String fields) throws Exception {
    var document = apiDocument();
    var envelope =
        schema(
            document,
            document
                .path("paths")
                .path(path)
                .at("/get/responses/200/content/application~1json/schema"));
    var data = schema(document, envelope.at("/properties/data"));
    var nested = schema(document, data.at(propertyPath));
    assertThat(mapper.valueToTree(responseValue).propertyNames())
        .containsExactlyInAnyOrder(fields.split(","));
    assertThat(nested.path("properties").propertyNames())
        .containsExactlyInAnyOrder(fields.split(","));
  }

  static Stream<Arguments> nestedResponseContracts() {
    UUID id = UUID.fromString("a693dfdc-03da-4cf7-af04-88f4917e4171");
    Instant now = Instant.parse("2026-11-12T00:00:00Z");
    var author = new SourceRecordAuthor(id, "합성 사용자", false);
    return Stream.of(
        Arguments.of(
            "/api/spaces/{spaceId}/items/{itemId}/evidence",
            "/properties/items/items",
            new CareItemEvidenceResponse.Evidence(
                id, "DIRECT_INPUT", "VALID", null, "합성 근거", id, now, List.of()),
            "evidenceId,kind,status,recordId,text,createdByMembershipId,createdAt,fieldPaths"),
        Arguments.of(
            "/api/spaces/{spaceId}/proposals/{proposalId}",
            "/properties/evidence/items",
            new ProposalDetail.Evidence(id, id, "합성 인용", true, author, now),
            "evidenceId,recordId,quote,valid,author,recordedAt"),
        Arguments.of(
            "/api/spaces/{spaceId}/deletion-impact",
            "/properties/counts",
            new CareSpaceDeletionImpact.Counts(1, 2, 3, 4, 5, 6),
            "members,records,items,proposals,requests,historyEntries"),
        Arguments.of(
            "/api/spaces/{spaceId}/source-records/{recordId}/deletion-impact",
            "/properties/counts",
            new SourceRecordDeletionImpact.Counts(1, 2, 3, 4),
            "proposals,evidence,items,historyEntries"),
        Arguments.of(
            "/api/share-links/{token}",
            "/properties/items/items",
            new SharedItemsResponse.Entry(id, SharedItemsResponse.State.DELETED, null),
            "itemId,state,item"),
        Arguments.of(
            "/api/spaces/{spaceId}/items/{itemId}/history",
            "/properties/items/items",
            new CareItemHistoryResponse.Entry("2", "UPDATE", author, now, List.of()),
            "version,changeKind,changedBy,changedAt,redactedFields"));
  }

  private JsonNode apiDocument() throws Exception {
    return mapper.readTree(
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode schema(JsonNode document, JsonNode node) {
    if (node.has("$ref")) {
      String reference = node.path("$ref").asText();
      assertThat(reference).startsWith("#/components/schemas/");
      node = document.at(reference.substring(1));
    }
    assertThat(node.isMissingNode()).isFalse();
    return node;
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
