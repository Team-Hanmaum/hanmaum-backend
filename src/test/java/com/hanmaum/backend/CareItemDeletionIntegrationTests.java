package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisResponse;
import com.hanmaum.backend.analysis.service.AnalysisExecutionService;
import com.hanmaum.backend.careitem.dto.*;
import com.hanmaum.backend.careitem.service.*;
import com.hanmaum.backend.carespace.dto.CreateCareSpaceRequest;
import com.hanmaum.backend.carespace.service.CareSpaceCreationService;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.security.oauth.MemberOAuth2User;
import com.hanmaum.backend.record.dto.CreateSourceRecordRequest;
import com.hanmaum.backend.record.dto.DeleteSourceRecordRequest;
import com.hanmaum.backend.record.service.SourceRecordCreationService;
import com.hanmaum.backend.record.service.SourceRecordDeletionImpactService;
import com.hanmaum.backend.record.service.SourceRecordDeletionService;
import java.time.Duration;
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

@SpringBootTest(
    properties = {
      "app.analysis.contract-version=v2", "app.analysis.worker.enabled=false",
      "app.ai.v2.max-request-bytes=1048576", "app.ai.v2.max-response-bytes=524288"
    })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CareItemDeletionIntegrationTests {
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc mvc;
  @Autowired CareSpaceCreationService spaces;
  @Autowired CareItemCreationService creation;
  @Autowired CareItemDeletionService deletions;
  @Autowired EditLockService locks;
  @Autowired CareItemQueryService queries;
  @Autowired SourceRecordCreationService records;
  @Autowired SourceRecordDeletionService sourceDeletions;
  @Autowired SourceRecordDeletionImpactService impacts;
  @Autowired AnalysisExecutionService execution;
  UUID owner, member, space, membership;

  @BeforeEach
  void setup() {
    owner = user();
    member = user();
    space =
        spaces.create(owner, new CreateCareSpaceRequest("합성 삭제 공간", UUID.randomUUID())).spaceId();
    membership = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO space_membership(id,space_id,user_id,joined_at) VALUES (?,?,?,now())",
        membership,
        space,
        member);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SCHEDULE", "TASK", "QUESTION", "OBSERVATION", "GUIDANCE"})
  void ownerDeletionPreservesDocumentsAndHistoryButExcludesItemsFromListsAndAnalysis(String type)
      throws Exception {
    UUID item = item(type);
    String before = getData(owner, "/items/" + item + "/evidence").toString();
    UUID document = document(item);
    var body = deletion(item, owner);
    var result = postData(owner, path(item), body, 200);
    assertThat(result.path("visibility").asText()).isEqualTo("DELETED");
    assertThat(result.path("version").asText()).isEqualTo("2");
    assertThat(result.path("deletedAt").asText()).isNotBlank();
    assertThat(postData(owner, path(item), body, 200)).isEqualTo(result);
    assertThat(getData(member, "/items/" + item).path("title").isNull()).isTrue();
    assertThat(getData(member, "/items/" + item).path("status").isNull()).isTrue();
    assertThat(queries.list(owner, space, type, null, null).items()).isEmpty();
    assertThat(queries.highlights(owner, space, java.time.Instant.now()).toString())
        .doesNotContain(item.toString());
    assertThat(getData(owner, "/items/" + item + "/evidence").toString()).isEqualTo(before);
    assertThat(getData(member, "/items/" + item + "/history").toString()).contains("DELETE");
    var history = getData(member, "/items/" + item + "/history/2");
    assertThat(history.at("/before/title").asText()).isEqualTo("보존할 합성 제목");
    assertThat(history.path("after").isNull()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT body IS NOT NULL FROM content_document WHERE id=?",
                Boolean.class,
                document))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM care_item_history WHERE item_id=?", Integer.class, item))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM care_item_edit_lock WHERE item_id=?", Integer.class, item))
        .isZero();
    var source =
        records.create(
            owner, space, new CreateSourceRecordRequest("삭제 이후 합성 원본", UUID.randomUUID()));
    var work =
        execution.claim(space, source.analysis().analysisId(), Duration.ofMinutes(2)).orElseThrow();
    assertThat(work.v2Request().candidates()).extracting(c -> c.itemId()).doesNotContain(item);
    postError(
        owner,
        path(item),
        body.deepCopy().put("clientRequestId", UUID.randomUUID().toString()),
        409,
        "ITEM_STATE_CONFLICT");
    postError(
        owner,
        path(item),
        body.deepCopy().put("expectedItemVersion", "2"),
        409,
        "IDEMPOTENCY_KEY_REUSED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"COMPLETED", "CANCELED"})
  void deletingAScheduleDoesNotRewriteItsBusinessStatus(String state) throws Exception {
    UUID item = item("SCHEDULE");
    jdbc.update("UPDATE care_item SET status=? WHERE id=?", state, item);
    postData(owner, path(item), deletion(item, owner), 200);
    assertThat(jdbc.queryForObject("SELECT status FROM care_item WHERE id=?", String.class, item))
        .isEqualTo(state);
  }

  @Test
  void authenticationOwnershipVersionAndOwnLeaseAreRequired() throws Exception {
    UUID item = item("TASK");
    var body = command().put("expectedItemVersion", "1").put("leaseToken", "a".repeat(43));
    mvc.perform(
            post(base() + path(item))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post(base() + path(item))
                .with(authentication(login(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isForbidden());
    postError(member, path(item), body, 403, "FORBIDDEN");
    postError(user(), path(item), body, 403, "FORBIDDEN");
    postError(owner, path(UUID.randomUUID()), body, 404, "RESOURCE_NOT_FOUND");
    postError(
        owner,
        path(item),
        body.deepCopy().put("expectedItemVersion", "2"),
        409,
        "VERSION_CONFLICT");
    postError(owner, path(item), body, 409, "EDIT_LOCK_EXPIRED");
    var other = locks.acquire(member, space, item, 1, EditLockPurpose.DELETION);
    assertThat(queries.detail(owner, space, item).editLock().holderMembershipId())
        .isEqualTo(membership);
    postError(owner, path(item), body, 409, "ITEM_LOCKED");
    locks.release(member, space, item, other.leaseToken());
    var valid = deletion(item, owner);
    postError(
        owner,
        path(item),
        valid.deepCopy().put("leaseToken", "a".repeat(43)),
        409,
        "EDIT_LOCK_EXPIRED");
    jdbc.update(
        "UPDATE care_item_edit_lock SET expires_at=now()-interval '1 second' WHERE item_id=?",
        item);
    postError(owner, path(item), valid, 409, "EDIT_LOCK_EXPIRED");
    assertThat(queries.detail(owner, space, item).version()).isEqualTo("1");
  }

  @Test
  void strictInputAndRequestSizeAreEnforcedBeforeMutation() throws Exception {
    UUID item = item("SCHEDULE");
    var body = deletion(item, owner);
    for (String field : List.of("expectedItemVersion", "leaseToken", "clientRequestId")) {
      var invalid = body.deepCopy();
      invalid.remove(field);
      postError(owner, path(item), invalid, 400, "INVALID_REQUEST");
    }
    postError(
        owner, path(item), body.deepCopy().put("expectedItemVersion", 1), 400, "INVALID_REQUEST");
    postError(
        owner, path(item), body.deepCopy().put("expectedItemVersion", "0"), 400, "INVALID_REQUEST");
    postError(
        owner, path(item), body.deepCopy().put("values", "unexpected"), 400, "INVALID_REQUEST");
    postError(
        owner,
        path(item),
        body.deepCopy().put("clientRequestId", "invalid"),
        400,
        "INVALID_REQUEST");
    mvc.perform(
            post(base() + path(item))
                .with(authentication(login(owner)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(" ".repeat(1048577)))
        .andExpect(status().isPayloadTooLarge());
    postData(owner, path(item), body, 200);
  }

  @Test
  void memberDeletionIsPendingUntilAnOwnerApprovesWithTheirOwnLease() throws Exception {
    UUID item = item("SCHEDULE");
    var submission = submission(item);
    var noLease = submission.deepCopy();
    ((ObjectNode) noLease.at("/entries/0")).remove("leaseToken");
    postError(member, "/change-requests", noLease, 409, "EDIT_LOCK_EXPIRED");
    var withValues = submission.deepCopy();
    ((ObjectNode) withValues.at("/entries/0")).putObject("values");
    postError(member, "/change-requests", withValues, 400, "INVALID_REQUEST");
    var submitted = postData(member, "/change-requests", submission, 201);
    UUID request = UUID.fromString(submitted.path("requestId").asText());
    assertThat(postData(member, "/change-requests", submission, 201)).isEqualTo(submitted);
    assertThat(queries.detail(owner, space, item).visibility()).isEqualTo("VISIBLE");
    assertThat(queries.detail(owner, space, item).editLock()).isNull();
    var detail = getData(owner, "/change-requests/" + request).at("/entries/0");
    assertThat(detail.path("action").asText()).isEqualTo("DELETE_ITEM");
    assertThat(detail.path("requestedValues").isNull()).isTrue();
    assertThat(detail.path("reviewToken").asText()).isNotBlank();
    var approval = batch();
    var entry = decision(detail).put("expectedItemVersion", "1");
    approval.withArray("entries").add(entry);
    postError(owner, "/request-applications", approval, 409, "EDIT_LOCK_EXPIRED");
    String lease = locks.acquire(owner, space, item, 1, EditLockPurpose.DELETION).leaseToken();
    entry.put("leaseToken", lease);
    var overridden = approval.deepCopy();
    ((ObjectNode) overridden.at("/entries/0")).putObject("values").put("title", "불필요한 값");
    postError(owner, "/request-applications", overridden, 400, "INVALID_REQUEST");
    postError(member, "/request-applications", approval, 403, "FORBIDDEN");
    var applied = postData(owner, "/request-applications", approval, 200);
    assertThat(postData(owner, "/request-applications", approval, 200)).isEqualTo(applied);
    assertThat(queries.detail(owner, space, item).visibility()).isEqualTo("DELETED");
    var decided = getData(owner, "/change-requests/" + request).at("/entries/0");
    assertThat(decided.path("status").asText()).isEqualTo("APPLIED");
    assertThat(decided.at("/decision/appliedValues").isNull()).isTrue();
    assertThat(decided.path("redactedFields").toString()).doesNotContain("decision.appliedValues");
    assertThat(postData(member, "/change-requests", submission, 201)).isEqualTo(submitted);
  }

  @Test
  void duplicatePendingDeletionDeclineAndCancellationPreserveVisibleItems() throws Exception {
    UUID item = item("SCHEDULE");
    var submitted = postData(member, "/change-requests", submission(item), 201);
    var duplicate = submission(item);
    postError(member, "/change-requests", duplicate, 409, "CHANGE_REQUEST_ALREADY_PENDING");
    var detail =
        getData(owner, "/change-requests/" + submitted.path("requestId").asText()).at("/entries/0");
    var decline = batch();
    decline.withArray("entries").add(decision(detail));
    postData(owner, "/request-declines", decline, 200);
    var next =
        postData(
            member,
            "/change-requests",
            duplicate.put("clientRequestId", UUID.randomUUID().toString()),
            201);
    var cancellation = batch();
    cancellation
        .withArray("entries")
        .addObject()
        .put("requestEntryId", next.at("/entries/0/requestEntryId").asText());
    postData(
        member,
        "/change-requests/" + next.path("requestId").asText() + "/cancellations",
        cancellation,
        200);
    assertThat(queries.detail(owner, space, item).visibility()).isEqualTo("VISIBLE");
    assertThat(queries.detail(owner, space, item).version()).isEqualTo("1");
  }

  @Test
  void selectedDeletionBatchRollsBackWhenAnotherEntryHasAnInvalidLease() throws Exception {
    UUID first = item("TASK"), second = item("GUIDANCE");
    var input = submission(first);
    input.withArray("entries").add(submission(second).at("/entries/0"));
    var submitted = postData(member, "/change-requests", input, 201);
    var details =
        getData(owner, "/change-requests/" + submitted.path("requestId").asText()).path("entries");
    var approval = batch();
    for (var detail : details) {
      UUID target = UUID.fromString(detail.path("targetItemId").asText());
      String lease = locks.acquire(owner, space, target, 1, EditLockPurpose.DELETION).leaseToken();
      approval
          .withArray("entries")
          .add(
              decision(detail)
                  .put("expectedItemVersion", "1")
                  .put("leaseToken", target.equals(first) ? lease : "a".repeat(43)));
    }
    postError(owner, "/request-applications", approval, 409, "EDIT_LOCK_EXPIRED");
    assertThat(queries.detail(owner, space, first).visibility()).isEqualTo("VISIBLE");
    assertThat(queries.detail(owner, space, second).visibility()).isEqualTo("VISIBLE");
    assertThat(
            getData(owner, "/change-requests/" + submitted.path("requestId").asText()).toString())
        .doesNotContain("APPLIED");
  }

  @Test
  void redactedItemsAllowDeletionRequestsWithoutRestoringTheirContent() throws Exception {
    UUID item = item("QUESTION");
    jdbc.update(
        "UPDATE content_document SET body=NULL,redacted_at=now() WHERE id=?", document(item));
    jdbc.update("UPDATE care_item SET needs_reconfirmation=true WHERE id=?", item);
    var submitted = postData(member, "/change-requests", submission(item), 201);
    var detail =
        getData(owner, "/change-requests/" + submitted.path("requestId").asText()).at("/entries/0");
    assertThat(detail.path("status").asText()).isEqualTo("PENDING");
    assertThat(detail.path("reviewToken").asText()).isNotBlank();
    var approval = batch();
    approval
        .withArray("entries")
        .add(
            decision(detail)
                .put("expectedItemVersion", "1")
                .put(
                    "leaseToken",
                    locks.acquire(owner, space, item, 1, EditLockPurpose.DELETION).leaseToken()));
    postData(owner, "/request-applications", approval, 200);
    assertThat(queries.detail(owner, space, item).visibility()).isEqualTo("DELETED");
    assertThat(getData(member, "/items/" + item + "/history/2").path("before").isNull()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT body IS NULL FROM content_document WHERE id=?",
                Boolean.class,
                document(item)))
        .isTrue();
  }

  @Test
  void reconfirmationLeaseCannotAuthorizeDeletionButNormalEditLeaseCan() throws Exception {
    UUID item = item("SCHEDULE");
    jdbc.update("UPDATE care_item SET needs_reconfirmation=true WHERE id=?", item);
    String reconfirm =
        locks.acquire(owner, space, item, 1, EditLockPurpose.RECONFIRMATION).leaseToken();
    postError(
        owner,
        path(item),
        command().put("expectedItemVersion", "1").put("leaseToken", reconfirm),
        409,
        "EDIT_LOCK_EXPIRED");
    locks.release(owner, space, item, reconfirm);
    jdbc.update("UPDATE care_item SET needs_reconfirmation=false WHERE id=?", item);
    String edit = locks.acquire(owner, space, item, 1).leaseToken();
    postData(
        owner, path(item), command().put("expectedItemVersion", "1").put("leaseToken", edit), 200);
  }

  @Test
  void erasingTheOriginalSourceStillRemovesRetainedHistoryAfterGeneralDeletion() throws Exception {
    UUID item = item("SCHEDULE"), evidence = UUID.randomUUID();
    var source =
        records.create(
            owner, space, new CreateSourceRecordRequest("후속 삭제할 합성 원본", UUID.randomUUID()));
    jdbc.update(
        "INSERT INTO evidence(id,space_id,record_id,record_version,quote,created_at) VALUES (?,?,?,1,'합성 근거',now())",
        evidence,
        space,
        source.recordId());
    jdbc.update(
        "INSERT INTO care_item_document_evidence(space_id,document_id,evidence_id) VALUES (?,?,?)",
        space,
        document(item),
        evidence);
    var body = deletion(item, owner);
    var deleted = postData(owner, path(item), body, 200);
    assertThat(getData(member, "/items/" + item + "/history/2").at("/before/title").asText())
        .isEqualTo("보존할 합성 제목");
    var impact = impacts.preview(owner, space, source.recordId());
    sourceDeletions.delete(
        owner,
        space,
        source.recordId(),
        new DeleteSourceRecordRequest(
            mapper.valueToTree("1"), mapper.valueToTree(impact.impactToken()), UUID.randomUUID()));
    assertThat(getData(member, "/items/" + item + "/history/2").path("before").isNull()).isTrue();
    assertThat(getData(member, "/items/" + item + "/history").toString()).contains("DELETE");
    assertThat(
            jdbc.queryForObject(
                "SELECT body IS NULL FROM content_document WHERE id=?",
                Boolean.class,
                document(item)))
        .isTrue();
    assertThat(queries.detail(owner, space, item).visibility()).isEqualTo("DELETED");
    assertThat(postData(owner, path(item), body, 200)).isEqualTo(deleted);
  }

  @Test
  void deletionInvalidatesOtherPendingRequestsAndInFlightAnalysis() throws Exception {
    UUID item = item("TASK");
    var submitted = postData(member, "/change-requests", submission(item), 201);
    var source =
        records.create(owner, space, new CreateSourceRecordRequest("합성 분석 원본", UUID.randomUUID()));
    var work =
        execution.claim(space, source.analysis().analysisId(), Duration.ofMinutes(2)).orElseThrow();
    assertThat(work.v2Request().candidates()).extracting(c -> c.itemId()).contains(item);
    postData(owner, path(item), deletion(item, owner), 200);
    assertThat(
            getData(owner, "/change-requests/" + submitted.path("requestId").asText())
                .at("/entries/0/status")
                .asText())
        .isEqualTo("INVALIDATED");
    assertThat(
            execution.completeV2(
                work, new AiV2AnalysisResponse(work.v2Request().requestId(), List.of())))
        .isFalse();
  }

  @Test
  void concurrentIdenticalRequestsRecordOneDeletionAndReturnTheSameResult() throws Exception {
    UUID item = item("SCHEDULE");
    var body = deletion(item, owner);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<Object> delete =
          () -> {
            start.await();
            try {
              return deletions.delete(owner, space, item, body);
            } catch (ApiException ex) {
              return ex.code().code();
            }
          };
      var a = pool.submit(delete);
      var b = pool.submit(delete);
      start.countDown();
      Object x = a.get(10, TimeUnit.SECONDS), y = b.get(10, TimeUnit.SECONDS);
      assertThat(List.of(x, y)).anyMatch(v -> v instanceof DeleteCareItemResponse);
      for (Object v : List.of(x, y)) {
        if (v instanceof String error) assertThat(error).isEqualTo("REQUEST_IN_PROGRESS");
        else assertThat(v).isEqualTo(deletions.delete(owner, space, item, body));
      }
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM care_item_history WHERE item_id=? AND operation='DELETE'",
                Integer.class,
                item))
        .isEqualTo(1);
    assertThat(queries.detail(owner, space, item).version()).isEqualTo("2");
  }

  @Test
  void aiProposalDeletionIsRejectedAndOpenApiDocumentsSuccessAndOwnLease() throws Exception {
    var invalid = batch();
    invalid
        .withArray("entries")
        .addObject()
        .put("proposalId", UUID.randomUUID().toString())
        .put("action", "DELETE_ITEM")
        .put("reviewToken", "a".repeat(64));
    postError(member, "/change-requests", invalid, 400, "INVALID_REQUEST");
    postError(owner, "/proposal-applications", invalid, 400, "INVALID_REQUEST");
    var result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
    var api = mapper.readTree(result.getResponse().getContentAsString());
    var operation =
        api.path("paths").path("/api/spaces/{spaceId}/items/{itemId}/deletions").path("post");
    assertThat(operation.at("/responses/200/content/application~1json/schema").isMissingNode())
        .isFalse();
    assertThat(operation.path("security").toString()).contains("SessionCookie", "CsrfToken");
    assertThat(operation.at("/responses/409").toString()).contains("DELETE_CARE_ITEM_CONFLICT");
    assertThat(api.at("/components/schemas/DeletionBody/required").toString())
        .contains("expectedItemVersion", "leaseToken", "clientRequestId");
    assertThat(api.at("/components/schemas/ApplicationEntry/properties/action/enum").toString())
        .contains("CREATE_ITEM", "UPDATE_ITEM", "RESOLVE_QUESTION", "LINK_EVIDENCE")
        .doesNotContain("DELETE_ITEM");
    assertThat(api.at("/components/schemas/SubmissionEntry/properties/action/enum").toString())
        .contains("DELETE_ITEM");
  }

  private UUID item(String type) {
    UUID item =
        creation
            .create(
                owner,
                space,
                new CreateCareItemRequest(
                    "SCHEDULE",
                    "보존할 합성 제목",
                    "보존할 설명",
                    new ScheduleFields(null, null, null),
                    UUID.randomUUID()))
            .itemId();
    if (!type.equals("SCHEDULE")) {
      String state =
          switch (type) {
            case "TASK" -> "NOT_STARTED";
            case "QUESTION" -> "UNCONFIRMED";
            default -> null;
          };
      jdbc.update("UPDATE care_item SET item_type=?,status=? WHERE id=?", type, state, item);
      jdbc.update("UPDATE care_item_history SET status=? WHERE item_id=?", state, item);
      jdbc.update(
          "UPDATE content_document SET body=jsonb_set(body,'{fields}','{}') WHERE id=?",
          document(item));
    }
    return item;
  }

  private UUID document(UUID item) {
    return jdbc.queryForObject(
        "SELECT current_document_id FROM care_item WHERE id=?", UUID.class, item);
  }

  private ObjectNode command() {
    return mapper.createObjectNode().put("clientRequestId", UUID.randomUUID().toString());
  }

  private ObjectNode batch() {
    var body = command();
    body.putArray("entries");
    return body;
  }

  private ObjectNode deletion(UUID item, UUID actor) {
    return command()
        .put("expectedItemVersion", "1")
        .put(
            "leaseToken",
            locks.acquire(actor, space, item, 1, EditLockPurpose.DELETION).leaseToken());
  }

  private ObjectNode submission(UUID item) {
    var lease = locks.acquire(member, space, item, 1, EditLockPurpose.DELETION).leaseToken();
    var body = batch();
    body.withArray("entries")
        .addObject()
        .put("sourceType", "DIRECT_INPUT")
        .put("action", "DELETE_ITEM")
        .put("targetItemId", item.toString())
        .put("expectedItemVersion", "1")
        .put("leaseToken", lease);
    return body;
  }

  private ObjectNode decision(JsonNode entry) {
    return mapper
        .createObjectNode()
        .put("requestEntryId", entry.path("requestEntryId").asText())
        .put("reviewToken", entry.path("reviewToken").asText());
  }

  private String path(UUID item) {
    return "/items/" + item + "/deletions";
  }

  private String base() {
    return "/api/spaces/" + space;
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
