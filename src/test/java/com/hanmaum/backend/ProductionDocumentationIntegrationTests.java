package com.hanmaum.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "DB_URL=jdbc:postgresql://unused.invalid/unused",
      "DB_USERNAME=unused",
      "DB_PASSWORD=synthetic-unused",
      "FRONTEND_URL=https://hanmaum.site",
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
