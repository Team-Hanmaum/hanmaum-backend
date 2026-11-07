package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

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
  @Autowired ObjectMapper mapper;

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
              suffix.equals("/proposal-applications") ? "proposalId" : "requestEntryId",
              "itemId",
              "itemVersion");
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
