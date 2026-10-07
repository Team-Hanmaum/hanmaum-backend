package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanmaum.backend.ai.client.AiServiceException;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.response.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, SecurityIntegrationTests.ProbeConfig.class})
class SecurityIntegrationTests {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @Test
  void rejectsAnonymousApiAccessWithJsonInsteadOfRedirecting() throws Exception {
    mvc.perform(get("/api/test/protected"))
        .andExpect(status().isUnauthorized())
        .andExpect(envelope(false, "UNAUTHENTICATED"));
  }

  @Test
  void exposesHealthWithoutDatabaseDetailsOrEnvelope() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist())
        .andExpect(jsonPath("$.success").doesNotExist());
  }

  @Test
  void issuesCsrfTokenInAPersistedSession() throws Exception {
    var response =
        mvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.success").doesNotExist())
            .andReturn()
            .getResponse();
    var cookie = response.getCookie("HANMAUM_SESSION");
    assertThat(cookie).isNotNull();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM spring_session_attributes WHERE attribute_name = ?",
                Integer.class,
                "org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN"))
        .isPositive();
  }

  @Test
  void rejectsMutationsWithoutCsrfEvenForAuthenticatedUsers() throws Exception {
    mvc.perform(
            post("/api/test/protected")
                .with(user("member"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"소식\"}"))
        .andExpect(status().isForbidden())
        .andExpect(envelope(false, "FORBIDDEN"));
  }

  @Test
  void allowsValidatedMutationsWithAuthenticationAndCsrf() throws Exception {
    mvc.perform(
            post("/api/test/protected")
                .with(user("member"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"소식\"}"))
        .andExpect(status().isOk())
        .andExpect(envelope(true, "SUCCESS"))
        .andExpect(jsonPath("$.data.title").value("소식"))
        .andExpect(jsonPath("$.errors").isEmpty());
  }

  @Test
  void supportsCreatedAcceptedAndNullSuccessData() throws Exception {
    mvc.perform(post("/api/test/created").with(user("member")).with(csrf()))
        .andExpect(status().isCreated())
        .andExpect(envelope(true, "SUCCESS"))
        .andExpect(jsonPath("$.data.title").value("created"));
    mvc.perform(post("/api/test/accepted").with(user("member")).with(csrf()))
        .andExpect(status().isAccepted())
        .andExpect(envelope(true, "ACCEPTED"))
        .andExpect(
            result ->
                assertThat(
                        mapper
                            .readTree(result.getResponse().getContentAsString())
                            .get("data")
                            .isNull())
                    .isTrue());
  }

  @Test
  void preservesNestedFieldPathsWithoutEchoingSubmittedValues() throws Exception {
    mvc.perform(
            post("/api/test/batch")
                .with(user("member"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entries\":[{\"title\":\"\"}],\"secret\":\"PRIVATE-RECORD\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(envelope(false, "INVALID_REQUEST"))
        .andExpect(jsonPath("$.errors[0].field").value("entries[0].title"))
        .andExpect(jsonPath("$.errors[0].reason").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors").doesNotExist())
        .andExpect(
            result ->
                assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("PRIVATE-RECORD", "rejectedValue", "stackTrace"));
  }

  @Test
  void returnsBadRequestForMalformedJsonWithoutEchoingIt() throws Exception {
    mvc.perform(
            post("/api/test/protected")
                .with(user("member"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{PRIVATE-RECORD"))
        .andExpect(status().isBadRequest())
        .andExpect(envelope(false, "INVALID_REQUEST"))
        .andExpect(jsonPath("$.errors").isEmpty())
        .andExpect(
            result ->
                assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("PRIVATE-RECORD"));
  }

  @Test
  void reportsMissingAndInvalidParametersByPublicFieldName() throws Exception {
    mvc.perform(get("/api/test/query").with(user("member")))
        .andExpect(status().isBadRequest())
        .andExpect(envelope(false, "INVALID_REQUEST"))
        .andExpect(jsonPath("$.errors[0].field").value("page"));
    mvc.perform(get("/api/test/query?page=0").with(user("member")))
        .andExpect(status().isBadRequest())
        .andExpect(envelope(false, "INVALID_REQUEST"))
        .andExpect(jsonPath("$.errors[0].field").value("page"));
    mvc.perform(get("/api/test/ids/PRIVATE-RECORD").with(user("member")))
        .andExpect(status().isBadRequest())
        .andExpect(envelope(false, "INVALID_REQUEST"))
        .andExpect(jsonPath("$.errors[0].field").value("id"))
        .andExpect(
            result ->
                assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("PRIVATE-RECORD"));
  }

  @Test
  void preservesFrameworkStatusAndAllowHeader() throws Exception {
    mvc.perform(get("/api/test/missing").with(user("member")))
        .andExpect(status().isNotFound())
        .andExpect(envelope(false, "RESOURCE_NOT_FOUND"));
    mvc.perform(put("/api/test/protected").with(user("member")).with(csrf()))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().exists("Allow"))
        .andExpect(envelope(false, "HTTP_405"));
    mvc.perform(
            post("/api/test/protected")
                .with(user("member"))
                .with(csrf())
                .contentType(MediaType.TEXT_PLAIN)
                .content("PRIVATE-RECORD"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(envelope(false, "HTTP_415"));
  }

  @Test
  void mapsKnownFailuresAndSanitizesUnexpectedFailures() throws Exception {
    mvc.perform(get("/api/test/failure/conflict").with(user("member")))
        .andExpect(status().isConflict())
        .andExpect(envelope(false, "VERSION_CONFLICT"));
    mvc.perform(get("/api/test/failure/forbidden").with(user("member")))
        .andExpect(status().isForbidden())
        .andExpect(envelope(false, "FORBIDDEN"));
    mvc.perform(get("/api/test/failure/ai").with(user("member")))
        .andExpect(status().isBadGateway())
        .andExpect(envelope(false, "AI_SERVICE_UNAVAILABLE"));
    mvc.perform(get("/api/test/failure/unexpected").with(user("member")))
        .andExpect(status().isInternalServerError())
        .andExpect(envelope(false, "INTERNAL_ERROR"))
        .andExpect(
            result ->
                assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("PRIVATE-RECORD"));
  }

  @Test
  void treatsInvalidReturnValuesAsServerErrors() throws Exception {
    mvc.perform(get("/api/test/invalid-return").with(user("member")))
        .andExpect(status().isInternalServerError())
        .andExpect(envelope(false, "INTERNAL_ERROR"));
  }

  @Test
  void requiresCsrfForLogoutAndInvalidatesThePersistedSession() throws Exception {
    mvc.perform(post("/api/auth/logout").with(user("member")))
        .andExpect(status().isForbidden())
        .andExpect(envelope(false, "FORBIDDEN"));
    var issuance = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse();
    var sessionCookie = issuance.getCookie("HANMAUM_SESSION");
    var token = mapper.readTree(issuance.getContentAsString()).get("token").asText();
    var logout =
        mvc.perform(post("/api/auth/logout").cookie(sessionCookie).header("X-CSRF-TOKEN", token))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""))
            .andReturn()
            .getResponse();
    assertThat(logout.getCookie("HANMAUM_SESSION").getMaxAge()).isZero();
    // Reusing the invalidated session and its token must not authorize another mutation.
    mvc.perform(post("/api/auth/logout").cookie(sessionCookie).header("X-CSRF-TOKEN", token))
        .andExpect(status().isForbidden())
        .andExpect(envelope(false, "FORBIDDEN"));
  }

  @Test
  void keepsOAuthRedirectsAndUsesTheCommonFailureEnvelope() throws Exception {
    mvc.perform(get("/oauth2/authorization/test-provider"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            result ->
                assertThat(result.getResponse().getRedirectedUrl())
                    .startsWith("https://identity.example/authorize?"));
    mvc.perform(
            get("/login/oauth2/code/test-provider")
                .param("code", "unused")
                .param("state", "unknown-state"))
        .andExpect(status().isUnauthorized())
        .andExpect(envelope(false, "OAUTH_LOGIN_FAILED"));
  }

  @Test
  void allowsOnlyConfiguredFrontendOrigins() throws Exception {
    mvc.perform(
            options("/api/test/protected")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    mvc.perform(
            options("/api/test/protected")
                .header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
  }

  @Test
  void publishesTheConfiguredOpenApiDocument() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info.title").value("한마음 API"));
  }

  private ResultMatcher envelope(boolean success, String code) {
    return result -> {
      var body = mapper.readTree(result.getResponse().getContentAsString());
      assertThat(body.size()).isEqualTo(6);
      for (String field : List.of("success", "code", "message", "data", "errors", "timestamp")) {
        assertThat(body.has(field)).as(field).isTrue();
      }
      assertThat(body.get("success").asBoolean()).isEqualTo(success);
      assertThat(body.get("code").asText()).isEqualTo(code);
      assertThat(body.get("message").asText()).isNotBlank();
      assertThat(body.get("errors").isArray()).isTrue();
      assertThat(Instant.parse(body.get("timestamp").asText())).isNotNull();
      if (!success) assertThat(body.get("data").isNull()).isTrue();
    };
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ProbeConfig {
    @Bean
    ProbeController probeController() {
      return new ProbeController();
    }

    @Bean
    ClientRegistrationRepository testClients() {
      return new InMemoryClientRegistrationRepository(
          ClientRegistration.withRegistrationId("test-provider")
              .clientId("test-client")
              .clientSecret("test-secret")
              .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
              .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
              .authorizationUri("https://identity.example/authorize")
              .tokenUri("https://identity.example/token")
              .userInfoUri("https://identity.example/user")
              .userNameAttributeName("sub")
              .build());
    }
  }

  // Test-only endpoints exercise the shared HTTP contract without implementing a domain.
  @RestController
  static class ProbeController {
    @GetMapping("/api/test/protected")
    ApiResponse<Input> get() {
      return ApiResponse.success(new Input("ok"));
    }

    @PostMapping("/api/test/protected")
    ApiResponse<Input> post(@Valid @RequestBody Input input) {
      return ApiResponse.success(input);
    }

    @PostMapping("/api/test/created")
    ResponseEntity<ApiResponse<Input>> created() {
      return ResponseEntity.status(201).body(ApiResponse.success(new Input("created")));
    }

    @PostMapping("/api/test/accepted")
    ResponseEntity<ApiResponse<Void>> accepted() {
      return ResponseEntity.accepted().body(ApiResponse.accepted(null));
    }

    @PostMapping("/api/test/batch")
    ApiResponse<Batch> batch(@Valid @RequestBody Batch input) {
      return ApiResponse.success(input);
    }

    @GetMapping("/api/test/query")
    ApiResponse<Integer> query(@RequestParam("page") @Min(1) int number) {
      return ApiResponse.success(number);
    }

    @GetMapping("/api/test/ids/{id}")
    ApiResponse<UUID> byId(@PathVariable UUID id) {
      return ApiResponse.success(id);
    }

    @GetMapping("/api/test/invalid-return")
    @NotBlank
    String invalidReturn() {
      return "";
    }

    @GetMapping("/api/test/failure/{kind}")
    ApiResponse<Void> failure(@PathVariable String kind) {
      throw switch (kind) {
        case "conflict" -> new ApiException(ErrorCode.VERSION_CONFLICT);
        case "forbidden" -> new AccessDeniedException("PRIVATE-RECORD");
        case "ai" -> new AiServiceException();
        default -> new IllegalStateException("PRIVATE-RECORD");
      };
    }
  }

  record Input(@NotBlank String title) {}

  record Batch(List<@Valid Input> entries, String secret) {}
}
