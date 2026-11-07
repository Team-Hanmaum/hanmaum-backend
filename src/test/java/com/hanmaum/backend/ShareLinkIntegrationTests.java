package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hanmaum.backend.careitem.dto.*;
import com.hanmaum.backend.careitem.service.*;
import com.hanmaum.backend.carespace.dto.*;
import com.hanmaum.backend.carespace.service.*;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.security.oauth.MemberOAuth2User;
import com.hanmaum.backend.record.dto.*;
import com.hanmaum.backend.record.service.*;
import com.hanmaum.backend.sharing.dto.*;
import com.hanmaum.backend.sharing.service.ShareLinkService;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ShareLinkIntegrationTests {
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc mvc;
  @Autowired CareSpaceCreationService spaces;
  @Autowired CareItemCreationService creation;
  @Autowired CareItemQueryService queries;
  @Autowired DirectCareItemUpdateService updates;
  @Autowired CareItemDeletionService deletions;
  @Autowired CareItemReconfirmationService reconfirmations;
  @Autowired EditLockService locks;
  @Autowired ShareLinkService sharing;
  @Autowired SourceRecordCreationService records;
  @Autowired SourceRecordDeletionService sourceDeletions;
  @Autowired SourceRecordDeletionImpactService impacts;
  @Autowired MembershipLifecycleService memberships;
  @Autowired PlatformTransactionManager transactions;
  @Autowired DataSource dataSource;
  UUID owner, member, space, membership;

  @BeforeEach
  void setup() {
    owner = user();
    member = user();
    space =
        spaces.create(owner, new CreateCareSpaceRequest("합성 공유 공간", UUID.randomUUID())).spaceId();
    membership = join(member);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SCHEDULE", "TASK", "QUESTION", "OBSERVATION", "GUIDANCE"})
  void minimalCardsSupportAllConfirmedTypesWithoutChangingItemOrSpace(String type)
      throws Exception {
    UUID item = item(type);
    var before = queries.detail(owner, space, item);
    var body = request(item);
    var result = issue(member, body);
    assertThat(result.propertyNames())
        .containsExactlyInAnyOrder("shareLinkId", "shareUrl", "cards");
    var card = result.at("/cards/0");
    assertThat(card.propertyNames()).containsExactlyInAnyOrder("itemType", "includesChange", "url");
    assertThat(card.path("itemType").asText()).isEqualTo(type);
    assertThat(card.path("includesChange").asBoolean()).isFalse();
    assertThat(card.path("url").asText()).isEqualTo(result.path("shareUrl").asText());
    assertThat(result.toString())
        .doesNotContain("합성 제목", "합성 설명", "합성 공유 공간", "합성 가족", item.toString());
    assertThat(issue(member, body)).isEqualTo(result);
    assertThat(read(owner, result).at("/items/0/item/title").asText()).isEqualTo("합성 제목");
    assertThat(queries.detail(owner, space, item)).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT version FROM care_space WHERE id=?", Long.class, space))
        .isEqualTo(1);
    String token = token(result);
    assertThat(token).matches("[A-Za-z0-9_-]{43}").doesNotContain(item.toString().replace("-", ""));
    assertThat(
            jdbc.queryForObject(
                "SELECT row_to_json(l)::text FROM share_link l WHERE id=?",
                String.class,
                linkId(result)))
        .doesNotContain(token, "합성 제목", "합성 설명");
    String receipt =
        jdbc.queryForObject(
            "SELECT result_refs::text FROM command_receipt WHERE actor_user_id=? AND client_request_id=?",
            String.class,
            member,
            UUID.fromString(body.path("clientRequestId").asText()));
    assertThat(mapper.readTree(receipt).propertyNames())
        .containsExactlyInAnyOrder("shareLinkId", "membershipId");
    assertThat(receipt).doesNotContain(token, "http", "합성 제목");
  }

  @ParameterizedTest
  @ValueSource(strings = {"COMPLETED", "CANCELED"})
  void schedulesKeepTheirBusinessStatusWhenShared(String state) throws Exception {
    UUID item = item("SCHEDULE");
    jdbc.update("UPDATE care_item SET status=? WHERE id=?", state, item);
    assertThat(read(member, issue(owner, request(item))).at("/items/0/item/status").asText())
        .isEqualTo(state);
  }

  @Test
  void loginCsrfAndCurrentParticipationAreRequiredWithoutNameDisclosure() throws Exception {
    UUID item = item("SCHEDULE");
    var body = request(item);
    mvc.perform(
            post(issuePath())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post(issuePath())
                .with(authentication(login(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isForbidden());
    UUID outsider = user();
    postError(outsider, body, 403, "FORBIDDEN");
    var result = issue(owner, body);
    mvc.perform(get(readPath(result))).andExpect(status().isUnauthorized());
    String error =
        mvc.perform(get(readPath(result)).with(authentication(login(outsider))))
            .andExpect(status().isForbidden())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(error).doesNotContain("합성 제목", "합성 공유 공간", token(result), item.toString());
    mvc.perform(get("/api/share-links/not-a-token").with(authentication(login(owner))))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/share-links/" + "z".repeat(43)).with(authentication(login(owner))))
        .andExpect(status().isNotFound());
  }

  @Test
  void strictInputRejectsUnknownDuplicateMissingAndCoercedFieldsAndOversizeBodies()
      throws Exception {
    UUID item = item("SCHEDULE");
    var valid = request(item);
    for (String field : List.of("clientRequestId", "targets")) {
      var body = valid.deepCopy();
      body.remove(field);
      postError(owner, body, 400, "INVALID_REQUEST");
    }
    for (String field : List.of("itemId", "expectedItemVersion", "includesChange")) {
      var body = valid.deepCopy();
      ((ObjectNode) body.at("/targets/0")).remove(field);
      postError(owner, body, 400, "INVALID_REQUEST");
    }
    var duplicate = valid.deepCopy();
    duplicate.withArray("targets").add(duplicate.at("/targets/0").deepCopy());
    postError(owner, duplicate, 400, "INVALID_REQUEST");
    var empty = valid.deepCopy();
    empty.putArray("targets");
    postError(owner, empty, 400, "INVALID_REQUEST");
    postError(owner, valid.deepCopy().put("expiresAt", "tomorrow"), 400, "INVALID_REQUEST");
    var unknown = valid.deepCopy();
    ((ObjectNode) unknown.at("/targets/0")).put("title", "hidden");
    postError(owner, unknown, 400, "INVALID_REQUEST");
    for (JsonNode bad :
        List.<JsonNode>of(
            mapper.valueToTree(1),
            mapper.valueToTree("0"),
            mapper.valueToTree("9223372036854775808"))) {
      var body = valid.deepCopy();
      ((ObjectNode) body.at("/targets/0")).set("expectedItemVersion", bad);
      postError(owner, body, 400, "INVALID_REQUEST");
    }
    var coerced = valid.deepCopy();
    ((ObjectNode) coerced.at("/targets/0")).put("includesChange", "true");
    postError(owner, coerced, 400, "INVALID_REQUEST");
    mvc.perform(
            post(issuePath())
                .with(authentication(login(owner)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(" ".repeat(1048577)))
        .andExpect(status().isPayloadTooLarge());
    assertThat(linkCount()).isZero();
  }

  @Test
  void allTargetsAreCheckedBeforeAnyLinkIsPersistedAndSelectionOrderIsPreserved() throws Exception {
    UUID first = item("SCHEDULE"), second = item("TASK");
    var invalid = request(first, second);
    ((ObjectNode) invalid.at("/targets/1")).put("expectedItemVersion", "2");
    postError(owner, invalid, 409, "VERSION_CONFLICT");
    assertThat(linkCount()).isZero();
    var absent = request(first, UUID.randomUUID());
    postError(owner, absent, 404, "RESOURCE_NOT_FOUND");
    UUID foreign =
        spaces.create(member, new CreateCareSpaceRequest("다른 합성 공간", UUID.randomUUID())).spaceId();
    UUID foreignItem =
        creation
            .create(
                member,
                foreign,
                new CreateCareItemRequest(
                    "SCHEDULE",
                    "다른 공간 제목",
                    null,
                    new ScheduleFields(null, null, null),
                    UUID.randomUUID()))
            .itemId();
    postError(owner, request(first, foreignItem), 404, "RESOURCE_NOT_FOUND");
    assertThat(linkCount()).isZero();
    var result = issue(owner, request(first, second));
    assertThat(read(member, result).path("items"))
        .extracting(n -> n.path("itemId").asText())
        .containsExactly(first.toString(), second.toString());
  }

  @Test
  void changeBadgeRequiresARealReadableConfirmedRevisionAndDoesNotTrustVersionAlone()
      throws Exception {
    UUID item = item("SCHEDULE");
    var body = request(item);
    ((ObjectNode) body.at("/targets/0")).put("includesChange", true);
    postError(owner, body, 409, "SHARE_TARGET_UNAVAILABLE");
    jdbc.update("UPDATE care_item SET version=2 WHERE id=?", item);
    ((ObjectNode) body.at("/targets/0")).put("expectedItemVersion", "2");
    postError(owner, body, 409, "SHARE_TARGET_UNAVAILABLE");
    jdbc.update("UPDATE care_item SET version=1 WHERE id=?", item);
    edit(item, "변경된 합성 제목");
    var result = issue(member, body);
    assertThat(result.at("/cards/0/includesChange").asBoolean()).isTrue();
    assertThat(read(owner, result).at("/items/0/item/title").asText()).isEqualTo("변경된 합성 제목");
    assertThat(result.toString()).doesNotContain("변경된 합성 제목");
  }

  @Test
  void openingALinkUsesLatestDetailsAndReplayRetainsTheOriginalMetadataAndUrl() throws Exception {
    UUID item = item("SCHEDULE");
    var body = request(item);
    var result = issue(owner, body);
    edit(item, "현재 합성 제목");
    assertThat(read(member, result).at("/items/0/item/title").asText()).isEqualTo("현재 합성 제목");
    assertThat(read(member, result).at("/items/0/item/version").asText()).isEqualTo("2");
    assertThat(issue(owner, body)).isEqualTo(result);
    postError(
        owner,
        body.deepCopy().put("clientRequestId", UUID.randomUUID().toString()),
        409,
        "VERSION_CONFLICT");
    var reused = body.deepCopy();
    ((ObjectNode) reused.at("/targets/0")).put("expectedItemVersion", "2");
    postError(owner, reused, 409, "IDEMPOTENCY_KEY_REUSED");
    ((ObjectNode) reused.at("/targets/0"))
        .put("expectedItemVersion", "1")
        .put("includesChange", true);
    postError(owner, reused, 409, "IDEMPOTENCY_KEY_REUSED");
    jdbc.update(
        "UPDATE share_link SET created_at=now()-interval '100 years' WHERE id=?", linkId(result));
    assertThat(read(member, result).at("/items/0/state").asText()).isEqualTo("AVAILABLE");
  }

  @Test
  void deletedAndReconfirmationTargetsExposeOnlyStateEvenWhenOtherFieldsRemain() throws Exception {
    UUID available = item("TASK"), deleted = item("SCHEDULE"), reconfirm = item("QUESTION");
    var body = request(available, deleted, reconfirm);
    var result = issue(owner, body);
    deletions.delete(
        owner,
        space,
        deleted,
        command()
            .put("expectedItemVersion", "1")
            .put(
                "leaseToken",
                locks.acquire(owner, space, deleted, 1, EditLockPurpose.DELETION).leaseToken()));
    jdbc.update("UPDATE care_item SET needs_reconfirmation=true WHERE id=?", reconfirm);
    var data = read(member, result);
    assertThat(data.at("/items/0/state").asText()).isEqualTo("AVAILABLE");
    assertThat(data.at("/items/1/state").asText()).isEqualTo("DELETED");
    assertThat(data.at("/items/2/state").asText()).isEqualTo("NEEDS_RECONFIRMATION");
    for (int i : List.of(1, 2)) {
      assertThat(data.at("/items/" + i + "/item").isNull()).isTrue();
      assertThat(data.at("/items/" + i).propertyNames())
          .containsExactlyInAnyOrder("itemId", "state", "item");
    }
    assertThat(issue(owner, body)).isEqualTo(result);
    postError(owner, request(deleted), 409, "VERSION_CONFLICT");
    var current = request(deleted);
    ((ObjectNode) current.at("/targets/0")).put("expectedItemVersion", "2");
    postError(owner, current, 409, "SHARE_TARGET_UNAVAILABLE");
    postError(owner, request(reconfirm), 409, "SHARE_TARGET_UNAVAILABLE");
  }

  @Test
  void sourceErasureHidesTheEntireTargetAndReconfirmationReopensOnlyNewContent() throws Exception {
    UUID item = item("SCHEDULE"), evidence = UUID.randomUUID();
    var source =
        records.create(owner, space, new CreateSourceRecordRequest("삭제할 합성 원문", UUID.randomUUID()));
    jdbc.update(
        "INSERT INTO evidence(id,space_id,record_id,record_version,quote,created_at) VALUES (?,?,?,1,'삭제할 합성 인용',now())",
        evidence,
        space,
        source.recordId());
    jdbc.update(
        "INSERT INTO care_item_document_evidence(space_id,document_id,evidence_id) VALUES (?,?,?)",
        space,
        document(item),
        evidence);
    var body = request(item);
    var result = issue(owner, body);
    var impact = impacts.preview(owner, space, source.recordId());
    sourceDeletions.delete(
        owner,
        space,
        source.recordId(),
        new DeleteSourceRecordRequest(
            mapper.valueToTree("1"), mapper.valueToTree(impact.impactToken()), UUID.randomUUID()));
    var erased = read(member, result);
    assertThat(erased.at("/items/0/state").asText()).isEqualTo("NEEDS_RECONFIRMATION");
    assertThat(erased.at("/items/0/item").isNull()).isTrue();
    assertThat(erased.toString()).doesNotContain("합성 제목", "합성 설명", "삭제할 합성");
    assertThat(issue(owner, body)).isEqualTo(result);
    long version = Long.parseLong(queries.detail(owner, space, item).version());
    var refresh =
        command()
            .put("expectedItemVersion", Long.toString(version))
            .put(
                "leaseToken",
                locks
                    .acquire(owner, space, item, version, EditLockPurpose.RECONFIRMATION)
                    .leaseToken())
            .put("basis", "새 합성 근거");
    refresh.putArray("evidenceIds");
    var values = refresh.putObject("values").put("title", "새로 확인한 제목").putNull("description");
    values.putObject("fields");
    reconfirmations.reconfirm(owner, space, item, refresh);
    var confirmed = read(member, result);
    assertThat(confirmed.at("/items/0/state").asText()).isEqualTo("AVAILABLE");
    assertThat(confirmed.at("/items/0/item/title").asText()).isEqualTo("새로 확인한 제목");
    assertThat(confirmed.toString()).doesNotContain("삭제할 합성", "합성 설명");
  }

  @Test
  void issuerDepartureDoesNotGrantOrRevokeOtherFamiliesAccessAndRejoinCannotReplayOldIssuance()
      throws Exception {
    var body = request(item("SCHEDULE"));
    var result = issue(member, body);
    memberships.depart(member, space, new MembershipChangeRequest("1", UUID.randomUUID()));
    mvc.perform(get(readPath(result)).with(authentication(login(member))))
        .andExpect(status().isForbidden());
    postError(member, body, 403, "FORBIDDEN");
    assertThat(read(owner, result).at("/items/0/state").asText()).isEqualTo("AVAILABLE");
    join(member);
    assertThat(read(member, result).at("/items/0/state").asText()).isEqualTo("AVAILABLE");
    postError(member, body, 403, "FORBIDDEN");
    issue(member, body.deepCopy().put("clientRequestId", UUID.randomUUID().toString()));
  }

  @Test
  void inactiveSpaceAndMissingTargetsReturnNotFound() throws Exception {
    var body = request(item("SCHEDULE"));
    var result = issue(owner, body);
    jdbc.update("DELETE FROM share_link_target WHERE share_link_id=?", linkId(result));
    mvc.perform(get(readPath(result)).with(authentication(login(owner))))
        .andExpect(status().isNotFound());
    jdbc.update("UPDATE care_space SET lifecycle='DELETING' WHERE id=?", space);
    mvc.perform(get(readPath(result)).with(authentication(login(owner))))
        .andExpect(status().isNotFound());
    postError(owner, body, 404, "RESOURCE_NOT_FOUND");
  }

  @Test
  void concurrentIdenticalIssuancePersistsExactlyOneLinkAndReplayIsStable() throws Exception {
    var body = request(item("SCHEDULE"));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<Object> call =
          () -> {
            start.await();
            try {
              return sharing.create(owner, space, body);
            } catch (ApiException ex) {
              return ex.code().code();
            }
          };
      var a = pool.submit(call);
      var b = pool.submit(call);
      start.countDown();
      var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
      assertThat(results).anyMatch(v -> v instanceof CreateShareLinkResponse);
      for (Object value : results) {
        if (value instanceof String error) assertThat(error).isEqualTo("REQUEST_IN_PROGRESS");
        else assertThat(value).isEqualTo(sharing.create(owner, space, body));
      }
    }
    assertThat(linkCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"VERSION", "ERASURE", "RECONFIRMATION", "DELETION", "DEPARTURE"})
  void issuanceRevalidatesAfterAnOverlappingSpaceLockedChange(String mutation) throws Exception {
    UUID item = item("SCHEDULE");
    var body = request(item);
    var locked = new CompletableFuture<Integer>();
    var issuanceStarted = new CompletableFuture<Integer>();
    var release = new CountDownLatch(1);
    var database = dataSource.unwrap(HikariDataSource.class);
    // The two application connections are occupied by the mutation and issuance. Observe PostgreSQL
    // through a separate connection rather than blocking on Hikari or inferring a lock from timing.
    try (var monitor =
            DriverManager.getConnection(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
        var pool = Executors.newFixedThreadPool(2)) {
      var change =
          pool.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          tx -> {
                            jdbc.queryForObject(
                                "SELECT id FROM care_space WHERE id=? FOR UPDATE",
                                UUID.class,
                                space);
                            locked.complete(
                                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            try {
                              if (!release.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("test lock timeout");
                            } catch (InterruptedException ex) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(ex);
                            }
                            switch (mutation) {
                              case "VERSION" ->
                                  jdbc.update("UPDATE care_item SET version=2 WHERE id=?", item);
                              case "ERASURE" ->
                                  jdbc.update(
                                      "UPDATE content_document SET body=null,redacted_at=now() WHERE id=?",
                                      document(item));
                              case "RECONFIRMATION" ->
                                  jdbc.update(
                                      "UPDATE care_item SET needs_reconfirmation=true WHERE id=?",
                                      item);
                              case "DELETION" ->
                                  jdbc.update(
                                      "UPDATE care_item SET visibility='DELETED' WHERE id=?", item);
                              case "DEPARTURE" ->
                                  jdbc.update(
                                      "UPDATE space_membership SET ended_at=now(),end_reason='LEFT' WHERE id=?",
                                      membership);
                              default -> throw new IllegalArgumentException();
                            }
                            return true;
                          }));
      int blocker = locked.get(5, TimeUnit.SECONDS);
      var issuance =
          pool.submit(
              () -> {
                try {
                  return new TransactionTemplate(transactions)
                      .execute(
                          tx -> {
                            issuanceStarted.complete(
                                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            sharing.create(member, space, body);
                            return "SUCCESS";
                          });
                } catch (ApiException ex) {
                  return ex.code().code();
                }
              });
      try {
        int waiter = issuanceStarted.get(5, TimeUnit.SECONDS);
        assertThat(awaitDatabaseBlock(monitor, waiter, blocker, issuance)).isTrue();
        assertThat(issuance.isDone()).isFalse();
      } finally {
        release.countDown();
      }
      assertThat(change.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(issuance.get(10, TimeUnit.SECONDS))
          .isEqualTo(
              switch (mutation) {
                case "VERSION" -> "VERSION_CONFLICT";
                case "DEPARTURE" -> "FORBIDDEN";
                default -> "SHARE_TARGET_UNAVAILABLE";
              });
    } finally {
      release.countDown();
    }
    assertThat(linkCount()).isZero();
  }

  /** Confirms the issuance backend actually waits on the transaction holding the space row. */
  private boolean awaitDatabaseBlock(
      Connection monitor, int waiter, int blocker, Future<?> issuance) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    try (var query =
        monitor.prepareStatement(
            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock' AND ?=ANY(pg_blocking_pids(pid)))")) {
      query.setInt(1, waiter);
      query.setInt(2, blocker);
      while (System.nanoTime() < deadline && !issuance.isDone()) {
        try (var result = query.executeQuery()) {
          if (result.next() && result.getBoolean(1)) return true;
        }
        Thread.sleep(20);
      }
      return false;
    }
  }

  @Test
  void swaggerDeclaresTypedSuccessStrictTargetsAndSessionAndCsrfTogether() throws Exception {
    var raw =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var api = mapper.readTree(raw);
    var post = api.path("paths").path("/api/spaces/{spaceId}/share-links").path("post");
    assertThat(post.at("/responses/201/content/application~1json/schema").isMissingNode())
        .isFalse();
    assertThat(post.path("security").size()).isEqualTo(1);
    assertThat(post.at("/security/0").propertyNames())
        .containsExactlyInAnyOrder("SessionCookie", "CsrfToken");
    var get = api.path("paths").path("/api/share-links/{token}").path("get");
    assertThat(get.at("/responses/200/content/application~1json/schema").isMissingNode()).isFalse();
    var schema = api.at("/components/schemas/CreateShareLinkBody");
    assertThat(schema.path("required"))
        .extracting(JsonNode::asText)
        .containsExactlyInAnyOrder("clientRequestId", "targets");
    assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    assertThat(schema.at("/properties/targets/minItems").asInt()).isEqualTo(1);
    assertThat(schema.at("/properties/targets/maxItems").isMissingNode()).isTrue();
    assertThat(api.at("/components/responses/CREATE_SHARE_LINK_CONFLICT").toString())
        .contains(
            "SHARE_TARGET_UNAVAILABLE",
            "VERSION_CONFLICT",
            "REQUEST_IN_PROGRESS",
            "IDEMPOTENCY_KEY_REUSED");
  }

  private void edit(UUID item, String title) {
    var body =
        command()
            .put("expectedItemVersion", "1")
            .put("leaseToken", locks.acquire(owner, space, item, 1).leaseToken());
    body.putObject("values").put("title", title);
    updates.update(owner, space, item, body, false);
  }

  private UUID item(String type) {
    UUID item =
        creation
            .create(
                owner,
                space,
                new CreateCareItemRequest(
                    "SCHEDULE",
                    "합성 제목",
                    "합성 설명",
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

  private int linkCount() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM share_link WHERE space_id=?", Integer.class, space);
  }

  private UUID linkId(JsonNode result) {
    return UUID.fromString(result.path("shareLinkId").asText());
  }

  private String token(JsonNode result) {
    String url = result.path("shareUrl").asText();
    return url.substring(url.lastIndexOf('/') + 1);
  }

  private String issuePath() {
    return "/api/spaces/" + space + "/share-links";
  }

  private String readPath(JsonNode result) {
    return "/api/share-links/" + token(result);
  }

  private ObjectNode command() {
    return mapper.createObjectNode().put("clientRequestId", UUID.randomUUID().toString());
  }

  private ObjectNode request(UUID... items) {
    var body = command();
    var targets = body.putArray("targets");
    for (UUID id : items)
      targets
          .addObject()
          .put("itemId", id.toString())
          .put("expectedItemVersion", "1")
          .put("includesChange", false);
    return body;
  }

  private JsonNode issue(UUID actor, JsonNode body) throws Exception {
    var result =
        mvc.perform(
                post(issuePath())
                    .with(authentication(login(actor)))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body.toString()))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString()).path("data");
  }

  private JsonNode read(UUID actor, JsonNode link) throws Exception {
    var result =
        mvc.perform(get(readPath(link)).with(authentication(login(actor))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString()).path("data");
  }

  private void postError(UUID actor, JsonNode body, int status, String code) throws Exception {
    mvc.perform(
            post(issuePath())
                .with(authentication(login(actor)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().is(status))
        .andExpect(jsonPath("$.code").value(code));
  }

  private UUID join(UUID actor) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO space_membership(id,space_id,user_id,joined_at) VALUES (?,?,?,now())",
        id,
        space,
        actor);
    return id;
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
