package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hanmaum.backend.carespace.dto.*;
import com.hanmaum.backend.carespace.service.*;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.security.oauth.MemberOAuth2User;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = "app.analysis.worker.enabled=false")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CareSpaceInformationIntegrationTests {
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc mvc;
  @Autowired CareSpaceCreationService creation;
  @Autowired CareSpaceInformationService updates;
  @Autowired CareSpaceQueryService queries;
  @Autowired MembershipLifecycleService memberships;
  UUID owner, member, space, memberMembership;

  @BeforeEach
  void setup() {
    owner = user();
    member = user();
    space = creation.create(owner, new CreateCareSpaceRequest("엄마", UUID.randomUUID())).spaceId();
    memberMembership = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO space_membership(id,space_id,user_id,joined_at) VALUES (?,?,?,now())",
        memberMembership,
        space,
        member);
  }

  @Test
  void ownerUpdatesAndReplaysTheOriginalNormalizedResultAfterALaterChange() throws Exception {
    var before = queries.detail(owner, space);
    var body = update("\u2003어머니\u00a0", "1");
    var saved = patchData(owner, body);
    assertThat(saved.path("subjectLabel").asText()).isEqualTo("어머니");
    assertThat(saved.path("version").asText()).isEqualTo("2");
    assertThat(queries.detail(member, space).subjectLabel()).isEqualTo("어머니");
    assertThat(queries.detail(member, space).createdAt()).isEqualTo(before.createdAt());
    patchData(owner, update("어머님", "2"));
    assertThat(patchData(owner, body.deepCopy().put("subjectLabel", "어머니"))).isEqualTo(saved);
    assertThat(queries.detail(owner, space).version()).isEqualTo("3");
    assertThat(queries.detail(owner, space).subjectLabel()).isEqualTo("어머님");
    assertThat(
            jdbc.queryForObject(
                "SELECT result_refs::text FROM command_receipt WHERE actor_user_id=? AND client_request_id=?",
                String.class,
                owner,
                UUID.fromString(body.path("clientRequestId").asText())))
        .doesNotContain("어머니", "subjectLabel");
    patchError(owner, body.deepCopy().put("subjectLabel", "다른 호칭"), 409, "IDEMPOTENCY_KEY_REUSED");
    patchError(
        owner, body.deepCopy().put("expectedSpaceVersion", "3"), 409, "IDEMPOTENCY_KEY_REUSED");
  }

  @Test
  void unchangedNormalizedLabelKeepsTheSpaceVersionAndTimestamp() throws Exception {
    var before = queries.detail(owner, space);
    var body = update("\u2003엄마\u00a0", "1");
    var result = patchData(owner, body);
    assertThat(result.path("version").asText()).isEqualTo("1");
    assertThat(queries.detail(owner, space).updatedAt()).isEqualTo(before.updatedAt());
    assertThat(patchData(owner, body)).isEqualTo(result);
  }

  @Test
  void authenticationCsrfCurrentOwnershipAndActiveSpaceAreRequired() throws Exception {
    var body = update("어머니", "1");
    mvc.perform(
            patch(base())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            patch(base())
                .with(authentication(login(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isForbidden());
    patchError(member, body, 403, "FORBIDDEN");
    patchError(user(), body, 403, "FORBIDDEN");
    var saved = patchData(owner, body);
    memberships.transfer(
        owner, space, new TransferOwnershipRequest(memberMembership, "2", UUID.randomUUID()));
    patchError(owner, body, 403, "FORBIDDEN");
    assertThat(saved.path("version").asText()).isEqualTo("2");
    jdbc.update("UPDATE care_space SET lifecycle='DELETING' WHERE id=?", space);
    patchError(member, update("어머님", "3"), 404, "RESOURCE_NOT_FOUND");
  }

  @Test
  void duplicateLabelIsScopedToTheCurrentOwnerAndDoesNotMutateTheSpace() throws Exception {
    creation.create(owner, new CreateCareSpaceRequest("아빠", UUID.randomUUID()));
    var before = queries.detail(owner, space);
    patchError(owner, update("\u2003아빠\u00a0", "1"), 409, "SPACE_LABEL_DUPLICATED");
    assertThat(queries.detail(owner, space)).isEqualTo(before);
    UUID other = user();
    creation.create(other, new CreateCareSpaceRequest("어머니", UUID.randomUUID()));
    patchData(owner, update("어머니", "1"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        "\u00a0\u2003",
        "엄마\n",
        "엄마\u2028",
        "엄마\u0000",
        "1234567890123456789012345678901"
      })
  void invalidLabelsFailWithoutChangingVersion(String label) throws Exception {
    patchError(owner, update(label, "1"), 400, "INVALID_REQUEST");
    assertThat(queries.detail(owner, space).version()).isEqualTo("1");
  }

  @Test
  void thirtyUnicodeCodePointsAndInternalWhitespaceArePreserved() throws Exception {
    String label = "😀".repeat(30);
    assertThat(patchData(owner, update(label, "1")).path("subjectLabel").asText()).isEqualTo(label);
    assertThat(patchData(owner, update("Mother  A", "2")).path("subjectLabel").asText())
        .isEqualTo("Mother  A");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "01", "+1", "1.0", " 1", "9223372036854775808"})
  void versionsMustBeCanonicalPositiveDecimalStrings(String version) throws Exception {
    patchError(owner, update("어머니", version), 400, "INVALID_REQUEST");
  }

  @Test
  void strictInputVersionConflictAndOverflowDoNotLeaveReceipts() throws Exception {
    var body = update("어머니", "1");
    for (String field : List.of("subjectLabel", "expectedSpaceVersion", "clientRequestId")) {
      var missing = body.deepCopy();
      missing.remove(field);
      patchError(owner, missing, 400, "INVALID_REQUEST");
    }
    patchError(owner, body.deepCopy().put("expectedSpaceVersion", 1), 400, "INVALID_REQUEST");
    patchError(owner, body.deepCopy().put("subjectLabel", 123), 400, "INVALID_REQUEST");
    patchError(
        owner, body.deepCopy().put("ownerUserId", member.toString()), 400, "INVALID_REQUEST");
    patchError(owner, body.deepCopy().put("expectedSpaceVersion", "2"), 409, "VERSION_CONFLICT");
    jdbc.update("UPDATE care_space SET version=? WHERE id=?", Long.MAX_VALUE, space);
    patchError(
        owner,
        body.deepCopy().put("expectedSpaceVersion", Long.toString(Long.MAX_VALUE)),
        409,
        "VERSION_CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM command_receipt WHERE actor_user_id=? AND command_kind='UPDATE_CARE_SPACE'",
                Integer.class,
                owner))
        .isZero();
  }

  @Test
  void concurrentRenamesToTheSameOwnerLabelHaveOneWinner() throws Exception {
    UUID other =
        creation.create(owner, new CreateCareSpaceRequest("아빠", UUID.randomUUID())).spaceId();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<String>>();
      for (UUID id : List.of(space, other))
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    updates.update(owner, id, update("부모님", "1"));
                    return "SUCCESS";
                  } catch (ApiException ex) {
                    return ex.code().code();
                  }
                }));
      start.countDown();
      assertThat(
              List.of(
                  futures.get(0).get(10, TimeUnit.SECONDS),
                  futures.get(1).get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("SUCCESS", "SPACE_LABEL_DUPLICATED");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM care_space WHERE owner_user_id=? AND subject_label='부모님' AND version=2",
                Integer.class,
                owner))
        .isEqualTo(1);
  }

  @Test
  void swaggerDescribesThePatchResponseRequiredFieldsAndCombinedSecurity() throws Exception {
    var result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
    var api = mapper.readTree(result.getResponse().getContentAsString());
    var operation = api.path("paths").path("/api/spaces/{spaceId}").path("patch");
    assertThat(operation.at("/responses/200/content/application~1json/schema").toString())
        .contains("ApiResponseUpdateCareSpaceResponse");
    assertThat(operation.path("security").toString()).contains("SessionCookie", "CsrfToken");
    assertThat(api.at("/components/schemas/CareSpaceUpdateBody/required").toString())
        .contains("subjectLabel", "expectedSpaceVersion", "clientRequestId");
  }

  private ObjectNode update(String label, String version) {
    return mapper
        .createObjectNode()
        .put("subjectLabel", label)
        .put("expectedSpaceVersion", version)
        .put("clientRequestId", UUID.randomUUID().toString());
  }

  private String base() {
    return "/api/spaces/" + space;
  }

  private JsonNode patchData(UUID actor, JsonNode body) throws Exception {
    var result =
        mvc.perform(
                patch(base())
                    .with(authentication(login(actor)))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString()).path("data");
  }

  private void patchError(UUID actor, JsonNode body, int status, String code) throws Exception {
    mvc.perform(
            patch(base())
                .with(authentication(login(actor)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().is(status))
        .andExpect(jsonPath("$.code").value(code));
  }

  private UUID user() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO app_user(id,display_name,created_at,updated_at) VALUES (?,'합성 가족',now(),now())",
        id);
    jdbc.update(
        "INSERT INTO social_account(id,user_id,provider,provider_user_id,created_at) VALUES (?,?,'KAKAO',?,now())",
        UUID.randomUUID(),
        id,
        id.toString());
    return id;
  }

  private OAuth2AuthenticationToken login(UUID id) {
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    return new OAuth2AuthenticationToken(
        new MemberOAuth2User(
            id, new DefaultOAuth2User(authorities, Map.of("id", id.toString()), "id")),
        authorities,
        "kakao");
  }
}
