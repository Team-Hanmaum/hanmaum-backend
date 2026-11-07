package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hanmaum.backend.carespace.dto.*;
import com.hanmaum.backend.carespace.service.*;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.security.oauth.MemberOAuth2User;
import com.hanmaum.backend.proposal.service.ChangeRequestService;
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
  @Autowired ChangeRequestService requests;
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
    assertThat(api.at("/components/schemas/ApplicationEntry/properties/action/enum").toString())
        .doesNotContain("UPDATE_SPACE");
    assertThat(api.at("/components/schemas/SubmissionEntry/properties/action/enum").toString())
        .contains("UPDATE_SPACE");
    assertThat(
            api.at(
                    "/components/schemas/ChangeRequestApplicationEntry/properties/expectedSpaceVersion")
                .isMissingNode())
        .isFalse();
  }

  @Test
  void memberRequestIsPendingUntilTheOwnerApprovesAndPreservesRequestedAndAppliedLabels()
      throws Exception {
    var submission = spaceSubmission("어머니");
    postError(owner, "/change-requests", submission, 403, "FORBIDDEN");
    var submitted = postData(member, "/change-requests", submission, 201);
    String request = submitted.path("requestId").asText();
    assertThat(queries.detail(owner, space).subjectLabel()).isEqualTo("엄마");
    assertThat(queries.detail(owner, space).version()).isEqualTo("1");
    var detail = getData(owner, "/change-requests/" + request).at("/entries/0");
    assertThat(detail.path("expectedSpaceVersion").asText()).isEqualTo("1");
    assertThat(detail.path("currentSpaceVersion").asText()).isEqualTo("1");
    assertThat(detail.at("/currentValues/subjectLabel").asText()).isEqualTo("엄마");
    assertThat(getData(member, "/change-requests/" + request).at("/entries/0/reviewToken").isNull())
        .isTrue();
    var approval = approval(detail);
    ((ObjectNode) approval.at("/entries/0")).putObject("values").put("subjectLabel", "  어머님  ");
    postError(member, "/request-applications", approval, 403, "FORBIDDEN");
    var applied = postData(owner, "/request-applications", approval, 200);
    assertThat(applied.at("/results/0/spaceVersion").asText()).isEqualTo("2");
    assertThat(applied.at("/results/0/itemId").isNull()).isTrue();
    assertThat(applied.at("/results/0/itemVersion").isNull()).isTrue();
    var decided = getData(owner, "/change-requests/" + request).at("/entries/0");
    assertThat(decided.path("status").asText()).isEqualTo("APPLIED");
    assertThat(decided.at("/requestedValues/subjectLabel").asText()).isEqualTo("어머니");
    assertThat(decided.at("/decision/appliedValues/subjectLabel").asText()).isEqualTo("어머님");
    assertThat(decided.at("/decision/spaceVersion").asText()).isEqualTo("2");
    assertThat(decided.path("currentSpaceVersion").asText()).isEqualTo("2");
    patchData(owner, update("어머니의 공간", "2"));
    assertThat(postData(owner, "/request-applications", approval, 200)).isEqualTo(applied);
    assertThat(postData(member, "/change-requests", submission, 201)).isEqualTo(submitted);
    assertThat(queries.detail(owner, space).version()).isEqualTo("3");
  }

  @Test
  void duplicatePendingSpaceRequestsCanBeCanceledAndResubmittedOrDeclined() throws Exception {
    var submitted = postData(member, "/change-requests", spaceSubmission("어머니"), 201);
    postError(
        member, "/change-requests", spaceSubmission("어머님"), 409, "CHANGE_REQUEST_ALREADY_PENDING");
    var cancel = batch();
    cancel
        .withArray("entries")
        .addObject()
        .put("requestEntryId", submitted.at("/entries/0/requestEntryId").asText());
    postData(
        member,
        "/change-requests/" + submitted.path("requestId").asText() + "/cancellations",
        cancel,
        200);
    var next = postData(member, "/change-requests", spaceSubmission("어머님"), 201);
    var detail =
        getData(owner, "/change-requests/" + next.path("requestId").asText()).at("/entries/0");
    var decline = batch();
    decline.withArray("entries").add(decision(detail));
    postData(owner, "/request-declines", decline, 200);
    assertThat(queries.detail(owner, space).subjectLabel()).isEqualTo("엄마");
    assertThat(queries.detail(owner, space).version()).isEqualTo("1");
    assertThat(
            getData(owner, "/change-requests/" + next.path("requestId").asText())
                .at("/entries/0/status")
                .asText())
        .isEqualTo("DECLINED");
  }

  @Test
  void changedSpaceVersionsInvalidatePendingRequestsWithoutAutomaticRebasing() throws Exception {
    var submitted = postData(member, "/change-requests", spaceSubmission("어머니"), 201);
    var detail =
        getData(owner, "/change-requests/" + submitted.path("requestId").asText()).at("/entries/0");
    var approval = approval(detail);
    patchData(owner, update("어머님", "1"));
    var stale =
        getData(owner, "/change-requests/" + submitted.path("requestId").asText()).at("/entries/0");
    assertThat(stale.path("status").asText()).isEqualTo("INVALIDATED");
    assertThat(stale.path("invalidReason").asText()).isEqualTo("SPACE_VERSION_CHANGED");
    assertThat(stale.path("reviewToken").isNull()).isTrue();
    postError(owner, "/request-applications", approval, 409, "VERSION_CONFLICT");
    assertThat(queries.detail(owner, space).version()).isEqualTo("2");
  }

  @Test
  void spaceRequestsRejectItemFieldsMissingVersionsAndAiProposalSources() throws Exception {
    for (String field : List.of("targetItemId", "expectedItemVersion", "leaseToken")) {
      var body = spaceSubmission("어머니");
      ((ObjectNode) body.at("/entries/0"))
          .put(field, field.equals("targetItemId") ? UUID.randomUUID().toString() : "1");
      postError(member, "/change-requests", body, 400, "INVALID_REQUEST");
    }
    var missing = spaceSubmission("어머니");
    ((ObjectNode) missing.at("/entries/0")).remove("expectedSpaceVersion");
    postError(member, "/change-requests", missing, 400, "INVALID_REQUEST");
    var ai = spaceSubmission("어머니");
    ((ObjectNode) ai.at("/entries/0"))
        .put("sourceType", "AI_PROPOSAL")
        .put("proposalId", UUID.randomUUID().toString())
        .put("reviewToken", "a".repeat(64));
    postError(member, "/change-requests", ai, 400, "INVALID_REQUEST");
    var submitted = postData(member, "/change-requests", spaceSubmission("어머니"), 201);
    var detail =
        getData(owner, "/change-requests/" + submitted.path("requestId").asText()).at("/entries/0");
    var approval = approval(detail);
    ((ObjectNode) approval.at("/entries/0")).remove("expectedSpaceVersion");
    postError(owner, "/request-applications", approval, 400, "INVALID_REQUEST");
    ((ObjectNode) approval.at("/entries/0")).put("expectedSpaceVersion", "2");
    postError(owner, "/request-applications", approval, 409, "VERSION_CONFLICT");
    ((ObjectNode) approval.at("/entries/0"))
        .put("expectedSpaceVersion", "1")
        .put("leaseToken", "a".repeat(43));
    postError(owner, "/request-applications", approval, 400, "INVALID_REQUEST");
  }

  @Test
  void selectedSpaceAndItemChangesAreValidatedBeforeAnyWriteAndApplyTogether() throws Exception {
    var body = spaceSubmission("어머니");
    var create =
        body.withArray("entries")
            .addObject()
            .put("sourceType", "DIRECT_INPUT")
            .put("action", "CREATE_ITEM");
    create
        .putObject("values")
        .put("itemType", "SCHEDULE")
        .put("title", "합성 일정")
        .putObject("fields");
    var submitted = postData(member, "/change-requests", body, 201);
    var details =
        getData(owner, "/change-requests/" + submitted.path("requestId").asText()).path("entries");
    var approve = approval(details.get(0));
    approve.withArray("entries").add(decision(details.get(1)));
    var invalid = approve.deepCopy();
    ((ObjectNode) invalid.at("/entries/1")).putObject("values").put("title", "");
    postError(owner, "/request-applications", invalid, 400, "INVALID_REQUEST");
    assertThat(queries.detail(owner, space).subjectLabel()).isEqualTo("엄마");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM care_item WHERE space_id=?", Integer.class, space))
        .isZero();
    var applied = postData(owner, "/request-applications", approve, 200);
    assertThat(applied.at("/results/0/spaceVersion").asText()).isEqualTo("2");
    assertThat(applied.at("/results/1/itemVersion").asText()).isEqualTo("1");
    assertThat(applied.at("/results/1/spaceVersion").isNull()).isTrue();
    assertThat(queries.detail(owner, space).version()).isEqualTo("2");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM care_item WHERE space_id=?", Integer.class, space))
        .isEqualTo(1);
  }

  @Test
  void aBatchCannotContainTwoEditsOfTheSameSpace() throws Exception {
    var body = spaceSubmission("어머니");
    body.withArray("entries").add(body.at("/entries/0").deepCopy());
    postError(member, "/change-requests", body, 409, "PROPOSAL_BATCH_CONFLICT");
    var first = postData(member, "/change-requests", spaceSubmission("어머니"), 201);
    UUID otherMember = user();
    jdbc.update(
        "INSERT INTO space_membership(id,space_id,user_id,joined_at) VALUES (?,?,?,now())",
        UUID.randomUUID(),
        space,
        otherMember);
    var second = postData(otherMember, "/change-requests", spaceSubmission("어머님"), 201);
    var a = getData(owner, "/change-requests/" + first.path("requestId").asText()).at("/entries/0");
    var b =
        getData(owner, "/change-requests/" + second.path("requestId").asText()).at("/entries/0");
    var approval = approval(a);
    approval.withArray("entries").add(decision(b).put("expectedSpaceVersion", "1"));
    postError(owner, "/request-applications", approval, 409, "PROPOSAL_BATCH_CONFLICT");
    assertThat(queries.detail(owner, space).version()).isEqualTo("1");
  }

  @Test
  void departureInvalidatesTheRequestAndRejoiningCannotReplayTheOldMembership() throws Exception {
    var submission = spaceSubmission("어머니");
    var submitted = postData(member, "/change-requests", submission, 201);
    memberships.depart(member, space, new MembershipChangeRequest("1", UUID.randomUUID()));
    var detail =
        getData(owner, "/change-requests/" + submitted.path("requestId").asText()).at("/entries/0");
    assertThat(detail.path("invalidReason").asText()).isEqualTo("REQUESTER_LEFT");
    jdbc.update(
        "INSERT INTO space_membership(id,space_id,user_id,joined_at) VALUES (?,?,?,now())",
        UUID.randomUUID(),
        space,
        member);
    postError(member, "/change-requests", submission, 403, "FORBIDDEN");
  }

  @Test
  void addingANullSpaceVersionDoesNotChangeExistingItemRequestIdempotency() throws Exception {
    var body = batch();
    body.withArray("entries")
        .addObject()
        .put("sourceType", "DIRECT_INPUT")
        .put("action", "CREATE_ITEM")
        .putObject("values")
        .put("itemType", "SCHEDULE")
        .put("title", "기존 항목 요청")
        .putObject("fields");
    var submitted = postData(member, "/change-requests", body, 201);
    ((ObjectNode) body.at("/entries/0")).putNull("expectedSpaceVersion");
    assertThat(postData(member, "/change-requests", body, 201)).isEqualTo(submitted);
  }

  @Test
  void approvingAnUnchangedLabelDoesNotBumpTheSpaceVersion() throws Exception {
    var before = queries.detail(owner, space);
    var submitted = postData(member, "/change-requests", spaceSubmission("  엄마  "), 201);
    var detail =
        getData(owner, "/change-requests/" + submitted.path("requestId").asText()).at("/entries/0");
    var applied = postData(owner, "/request-applications", approval(detail), 200);
    assertThat(applied.at("/results/0/spaceVersion").asText()).isEqualTo("1");
    assertThat(queries.detail(owner, space).updatedAt()).isEqualTo(before.updatedAt());
    assertThat(
            getData(owner, "/change-requests/" + submitted.path("requestId").asText())
                .at("/entries/0/status")
                .asText())
        .isEqualTo("APPLIED");
  }

  @Test
  void concurrentSpaceApprovalsUseOneVersionAndLeaveTheLosingRequestInvalidated() throws Exception {
    UUID other = user();
    jdbc.update(
        "INSERT INTO space_membership(id,space_id,user_id,joined_at) VALUES (?,?,?,now())",
        UUID.randomUUID(),
        space,
        other);
    var first = postData(member, "/change-requests", spaceSubmission("어머니"), 201);
    var second = postData(other, "/change-requests", spaceSubmission("어머님"), 201);
    var a =
        approval(
            getData(owner, "/change-requests/" + first.path("requestId").asText())
                .at("/entries/0"));
    var b =
        approval(
            getData(owner, "/change-requests/" + second.path("requestId").asText())
                .at("/entries/0"));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<String>>();
      for (var body : List.of(a, b))
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    requests.apply(owner, space, body);
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
          .containsExactlyInAnyOrder("SUCCESS", "VERSION_CONFLICT");
    }
    assertThat(queries.detail(owner, space).version()).isEqualTo("2");
    var statuses =
        List.of(
            getData(owner, "/change-requests/" + first.path("requestId").asText())
                .at("/entries/0/status")
                .asText(),
            getData(owner, "/change-requests/" + second.path("requestId").asText())
                .at("/entries/0/status")
                .asText());
    assertThat(statuses).containsExactlyInAnyOrder("APPLIED", "INVALIDATED");
  }

  private ObjectNode batch() {
    var body = mapper.createObjectNode().put("clientRequestId", UUID.randomUUID().toString());
    body.putArray("entries");
    return body;
  }

  private ObjectNode spaceSubmission(String label) {
    var body = batch();
    body.withArray("entries")
        .addObject()
        .put("sourceType", "DIRECT_INPUT")
        .put("action", "UPDATE_SPACE")
        .put("expectedSpaceVersion", "1")
        .putObject("values")
        .put("subjectLabel", label);
    return body;
  }

  private ObjectNode decision(JsonNode detail) {
    return mapper
        .createObjectNode()
        .put("requestEntryId", detail.path("requestEntryId").asText())
        .put("reviewToken", detail.path("reviewToken").asText());
  }

  private ObjectNode approval(JsonNode detail) {
    var body = batch();
    body.withArray("entries").add(decision(detail).put("expectedSpaceVersion", "1"));
    return body;
  }

  private JsonNode getData(UUID actor, String path) throws Exception {
    var result =
        mvc.perform(get(base() + path).with(authentication(login(actor))))
            .andExpect(status().isOk())
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString()).path("data");
  }

  private JsonNode postData(UUID actor, String path, JsonNode body, int expected) throws Exception {
    var result =
        mvc.perform(
                post(base() + path)
                    .with(authentication(login(actor)))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body.toString()))
            .andExpect(status().is(expected))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString()).path("data");
  }

  private void postError(UUID actor, String path, JsonNode body, int expected, String code)
      throws Exception {
    mvc.perform(
            post(base() + path)
                .with(authentication(login(actor)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().is(expected))
        .andExpect(jsonPath("$.code").value(code));
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
