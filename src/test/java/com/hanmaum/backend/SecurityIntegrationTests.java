package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, SecurityIntegrationTests.ProbeConfig.class})
class SecurityIntegrationTests {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @Test
  void rejectsAnonymousApiAccessWithJsonInsteadOfRedirecting() throws Exception {
    mvc.perform(get("/api/test/protected"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void exposesHealthWithoutDatabaseDetails() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist());
  }

  @Test
  void issuesCsrfTokenInAPersistedSession() throws Exception {
    var response =
        mvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
            .andExpect(jsonPath("$.token").isNotEmpty())
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
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
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
        .andExpect(jsonPath("$.title").value("소식"));
  }

  @Test
  void returnsFieldErrorsWithoutEchoingSubmittedValues() throws Exception {
    mvc.perform(
            post("/api/test/protected")
                .with(user("member"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.fieldErrors.title").exists());
  }

  @Test
  void returnsBadRequestForMalformedJson() throws Exception {
    mvc.perform(
            post("/api/test/protected")
                .with(user("member"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{broken"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("HTTP_400"));
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

  @TestConfiguration(proxyBeanMethods = false)
  static class ProbeConfig {
    @Bean
    ProbeController probeController() {
      return new ProbeController();
    }
  }

  @RestController
  static class ProbeController {
    @GetMapping("/api/test/protected")
    Input get() {
      return new Input("ok");
    }

    @PostMapping("/api/test/protected")
    Input post(@Valid @RequestBody Input input) {
      return input;
    }
  }

  record Input(@NotBlank String title) {}
}
