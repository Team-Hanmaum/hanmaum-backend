package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hanmaum.backend.ai.dto.*;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisResponse;
import com.hanmaum.backend.analysis.repository.AnalysisRepository;
import com.hanmaum.backend.analysis.service.AnalysisExecutionService;
import com.hanmaum.backend.careitem.dto.*;
import com.hanmaum.backend.careitem.service.*;
import com.hanmaum.backend.carespace.dto.*;
import com.hanmaum.backend.carespace.repository.CareSpaceDeletionRepository;
import com.hanmaum.backend.carespace.service.*;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.security.oauth.MemberOAuth2User;
import com.hanmaum.backend.record.dto.*;
import com.hanmaum.backend.record.service.SourceRecordCreationService;
import com.hanmaum.backend.sharing.service.ShareLinkService;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.time.Duration;
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

@SpringBootTest(
    properties = {
      "app.analysis.worker.enabled=false",
      "app.ai.v2.max-request-bytes=1048576",
      "app.ai.v2.max-response-bytes=524288"
    })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CareSpaceDeletionIntegrationTests {
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc mvc;
  @Autowired CareSpaceCreationService spaces;
  @Autowired CareSpaceQueryService spaceQueries;
  @Autowired CareSpaceDeletionImpactService impacts;
  @Autowired CareSpaceDeletionService deletions;
  @Autowired CareSpaceDeletionRepository deletionRows;
  @Autowired CareItemCreationService items;
  @Autowired DirectCareItemUpdateService updates;
  @Autowired EditLockService locks;
  @Autowired SourceRecordCreationService records;
  @Autowired AnalysisExecutionService execution;
  @Autowired AnalysisRepository runs;
  @Autowired InvitationService invitations;
  @Autowired ShareLinkService sharing;
  @Autowired MembershipLifecycleService memberships;
  @Autowired PlatformTransactionManager transactions;
  @Autowired DataSource dataSource;
  UUID owner, member, space, ownerMembership, memberMembership;
  CreateCareSpaceRequest originalCreation;

  @BeforeEach
  void setup() {
    owner = user();
    member = user();
    originalCreation = new CreateCareSpaceRequest("삭제할 합성 공간", UUID.randomUUID());
    var created = spaces.create(owner, originalCreation);
    space = created.spaceId();
    ownerMembership = created.membershipId();
    memberMembership = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO space_membership(id,space_id,user_id,joined_at) VALUES (?,?,?,now())",
        memberMembership,
        space,
        member);
  }

  @Test
  void previewCountsAllStatesWithoutCopyingCareTextOrChangingRows() throws Exception {
    var graph = populateGraph();
    jdbc.update(
        "UPDATE space_membership SET ended_at=now(),end_reason='LEFT' WHERE id=?",
        memberMembership);
    jdbc.update(
        "UPDATE source_record SET body=null,lifecycle='ERASED',erased_at=now(),version=2 WHERE space_id=?",
        space);
    jdbc.update("UPDATE care_item SET visibility='DELETED' WHERE space_id=?", space);
    jdbc.update("UPDATE ai_proposal SET invalidated_at=now() WHERE space_id=?", space);
    jdbc.update(
        "UPDATE analysis_run SET status='DISCARDED',invalidated_at=now(),worker_token=null,lease_until=null,failure_code=null,finished_at=now() WHERE space_id=?",
        space);
    var before = counts(space);
    var data = preview(owner);
    assertThat(data.propertyNames())
        .containsExactlyInAnyOrder("spaceId", "spaceVersion", "counts", "impactToken");
    assertThat(data.path("counts").propertyNames())
        .containsExactlyInAnyOrder(
            "members", "records", "items", "proposals", "requests", "historyEntries");
    assertThat(data.at("/counts/members").asLong()).isEqualTo(3);
    assertThat(data.at("/counts/records").asLong()).isEqualTo(1);
    assertThat(data.at("/counts/items").asLong()).isEqualTo(1);
    assertThat(data.at("/counts/proposals").asLong()).isEqualTo(1);
    assertThat(data.at("/counts/requests").asLong()).isEqualTo(1);
    assertThat(data.at("/counts/historyEntries").asLong()).isEqualTo(2);
    assertThat(data.toString())
        .doesNotContain("삭제할 합성", "합성 가족", graph.shareToken(), graph.invitationToken());
    assertThat(data.path("impactToken").asText()).matches("[0-9a-f]{64}");
    assertThat(preview(owner)).isEqualTo(data);
    assertThat(counts(space)).isEqualTo(before);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              var metadata = deletionRows.snapshot(space).toString();
              assertThat(metadata)
                  .doesNotContain("삭제할 합성", graph.shareToken(), graph.invitationToken());
            });
    delete(owner, command(data));
    assertAllGone();
  }

  /** Erases every populated FK branch while retaining other spaces, accounts, and replay guards. */
  @Test
  void deletionRemovesEverySpaceOwnedTableButPreservesUsersOtherSpacesAndReplayGuards()
      throws Exception {
    var graph = populateGraph();
    assertThat(counts(space))
        .allSatisfy((table, count) -> assertThat(count).as(table).isPositive());
    UUID other =
        spaces.create(member, new CreateCareSpaceRequest("보존할 공간", UUID.randomUUID())).spaceId();
    records.create(member, other, new CreateSourceRecordRequest("보존할 다른 공간 원문", UUID.randomUUID()));
    var otherCounts = counts(other);
    var users =
        jdbc.queryForList("SELECT id,display_name,created_at,updated_at FROM app_user ORDER BY id");
    var social = jdbc.queryForList("SELECT * FROM social_account ORDER BY id");
    var body = command(preview(owner));
    var response = delete(owner, body);
    assertThat(response.propertyNames()).containsExactlyInAnyOrder("spaceId", "deletedAt");
    assertThat(response.path("spaceId").asText()).isEqualTo(space.toString());
    assertThat(response.path("deletedAt").asText()).isNotBlank();
    assertAllGone();
    assertThat(counts(other)).isEqualTo(otherCounts);
    assertThat(
            jdbc.queryForList(
                "SELECT id,display_name,created_at,updated_at FROM app_user ORDER BY id"))
        .isEqualTo(users);
    assertThat(jdbc.queryForList("SELECT * FROM social_account ORDER BY id")).isEqualTo(social);
    var refs =
        jdbc.queryForObject(
            "SELECT result_refs::text FROM command_receipt WHERE actor_user_id=? AND client_request_id=?",
            String.class,
            owner,
            UUID.fromString(body.path("clientRequestId").asText()));
    assertThat(mapper.readTree(refs).propertyNames()).containsExactly("deletedAt");
    assertThat(refs).doesNotContain("삭제할 합성", body.path("impactToken").asText());
    postError(owner, body, 404, "RESOURCE_NOT_FOUND");
    postError(
        owner, body.deepCopy().put("expectedSpaceVersion", "99"), 409, "IDEMPOTENCY_KEY_REUSED");
    postError(
        owner,
        body.deepCopy().put("clientRequestId", UUID.randomUUID().toString()),
        404,
        "RESOURCE_NOT_FOUND");
    assertCode(() -> spaces.create(owner, originalCreation), "RESOURCE_NOT_FOUND");
    assertCode(
        () -> invitations.join(owner, graph.invitationToken(), UUID.randomUUID()),
        "INVITATION_UNAVAILABLE");
    assertCode(() -> sharing.read(owner, graph.shareToken()), "RESOURCE_NOT_FOUND");
    var staleEdit =
        mapper
            .createObjectNode()
            .put("clientRequestId", UUID.randomUUID().toString())
            .put("expectedItemVersion", "2")
            .put("leaseToken", graph.leaseToken());
    staleEdit.putObject("values").put("title", "복구하면 안 되는 제목");
    assertCode(
        () -> updates.update(owner, space, graph.item(), staleEdit, false), "RESOURCE_NOT_FOUND");
    for (UUID actor : List.of(owner, member)) {
      for (String path :
          List.of(
              base(),
              base() + "/deletion-impact",
              base() + "/source-records",
              base() + "/items",
              base() + "/proposals",
              base() + "/change-requests",
              base() + "/dashboard",
              base() + "/members"))
        mvc.perform(get(path).with(authentication(login(actor)))).andExpect(status().isNotFound());
      mvc.perform(get("/api/share-links/" + graph.shareToken()).with(authentication(login(actor))))
          .andExpect(status().isNotFound());
    }
    assertThat(spaceQueries.list(owner).items()).noneMatch(s -> s.spaceId().equals(space));
    var fresh =
        spaces.create(
            owner, new CreateCareSpaceRequest(originalCreation.subjectLabel(), UUID.randomUUID()));
    assertThat(fresh.spaceId()).isNotEqualTo(space);
    assertCode(() -> spaces.create(owner, originalCreation), "RESOURCE_NOT_FOUND");
  }

  /** Rejects unauthenticated, non-owner, missing-space, and missing-CSRF deletion attempts. */
  @Test
  void authenticationOwnerRoleCsrfAndSpaceExistenceAreChecked() throws Exception {
    var body = command(preview(owner));
    mvc.perform(get(base() + "/deletion-impact")).andExpect(status().isUnauthorized());
    mvc.perform(
            post(base() + "/deletions")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post(base() + "/deletions")
                .with(authentication(login(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isForbidden());
    for (UUID actor : List.of(member, user())) {
      var error =
          mvc.perform(get(base() + "/deletion-impact").with(authentication(login(actor))))
              .andExpect(status().isForbidden())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(error).doesNotContain("삭제할 합성 공간", body.path("impactToken").asText());
      postError(actor, body, 403, "FORBIDDEN");
    }
    UUID previous = space;
    space = UUID.randomUUID();
    mvc.perform(get(base() + "/deletion-impact").with(authentication(login(owner))))
        .andExpect(status().isNotFound());
    postError(owner, body, 404, "RESOURCE_NOT_FOUND");
    space = previous;
    jdbc.update("UPDATE care_space SET lifecycle='DELETING' WHERE id=?", space);
    postError(owner, body, 404, "RESOURCE_NOT_FOUND");
    jdbc.update("UPDATE care_space SET lifecycle='ACTIVE' WHERE id=?", space);
  }

  /** Rejects incomplete/coerced/unknown input and oversized raw bodies before deletion begins. */
  @Test
  void strictInputRejectsMissingCoercedAndUnknownFieldsAndOversizedBodies() throws Exception {
    var valid = command(preview(owner));
    for (String field : List.of("expectedSpaceVersion", "impactToken", "clientRequestId")) {
      var invalid = valid.deepCopy();
      invalid.remove(field);
      postError(owner, invalid, 400, "INVALID_REQUEST");
    }
    for (JsonNode version :
        List.<JsonNode>of(
            mapper.valueToTree(1),
            mapper.valueToTree("0"),
            mapper.valueToTree("01"),
            mapper.valueToTree("9223372036854775808"))) {
      var invalid = valid.deepCopy();
      invalid.set("expectedSpaceVersion", version);
      postError(owner, invalid, 400, "INVALID_REQUEST");
    }
    postError(owner, valid.deepCopy().put("impactToken", "bad"), 400, "INVALID_REQUEST");
    postError(owner, valid.deepCopy().put("clientRequestId", "bad"), 400, "INVALID_REQUEST");
    postError(owner, valid.deepCopy().put("confirmed", true), 400, "INVALID_REQUEST");
    mvc.perform(
            post(base() + "/deletions")
                .with(authentication(login(owner)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(" ".repeat(1048577)))
        .andExpect(status().isPayloadTooLarge());
    assertThat(counts(space).get("care_space")).isEqualTo(1);
  }

  @Test
  void versionImpactTokenAndOriginalActorAndSpaceMustMatch() throws Exception {
    var body = command(preview(owner));
    postError(
        owner,
        body.deepCopy().put("clientRequestId", originalCreation.clientRequestId().toString()),
        409,
        "IDEMPOTENCY_KEY_REUSED");
    postError(owner, body.deepCopy().put("expectedSpaceVersion", "2"), 409, "VERSION_CONFLICT");
    postError(
        owner, body.deepCopy().put("impactToken", "a".repeat(64)), 409, "DELETION_IMPACT_CHANGED");
    memberships.transfer(
        owner, space, new TransferOwnershipRequest(memberMembership, "1", UUID.randomUUID()));
    var current = command(preview(member));
    assertThat(current.path("impactToken").asText())
        .isNotEqualTo(body.path("impactToken").asText());
    postError(
        member,
        current.deepCopy().put("impactToken", body.path("impactToken").asText()),
        409,
        "DELETION_IMPACT_CHANGED");
    UUID original = space;
    space =
        spaces.create(member, new CreateCareSpaceRequest("별도 소유 공간", UUID.randomUUID())).spaceId();
    var other = command(preview(member));
    postError(
        member,
        other.deepCopy().put("impactToken", current.path("impactToken").asText()),
        409,
        "DELETION_IMPACT_CHANGED");
    space = original;
  }

  /** Detects graph changes beyond the space version and the six user-facing aggregate counts. */
  @ParameterizedTest
  @ValueSource(strings = {"RECORD", "INVITATION", "SHARE", "BASIS", "EDIT", "REQUEST", "ANALYSIS"})
  void scopeChangesInvalidateThePreviewEvenWhenSpaceVersionAndPrimaryCountsDoNotChange(
      String mutation) throws Exception {
    UUID item = createItem();
    var before = preview(owner);
    var body = command(before);
    switch (mutation) {
      case "RECORD" ->
          records.create(owner, space, new CreateSourceRecordRequest("새 합성 원문", UUID.randomUUID()));
      case "INVITATION" -> invitations.issue(owner, space, UUID.randomUUID());
      case "SHARE" -> sharing.create(owner, space, shareRequest(item, "1"));
      case "BASIS" ->
          jdbc.update(
              "INSERT INTO care_item_basis(id,space_id,item_id,direct_document_id,submitted_by_membership_id,created_at) VALUES (?,?,?,?,?,now())",
              UUID.randomUUID(),
              space,
              item,
              newDocument("DIRECT_INPUT"),
              ownerMembership);
      case "EDIT" -> {
        var edit =
            mapper
                .createObjectNode()
                .put("clientRequestId", UUID.randomUUID().toString())
                .put("expectedItemVersion", "1")
                .put("leaseToken", locks.acquire(owner, space, item, 1).leaseToken());
        edit.putObject("values").put("title", "새로 수정한 합성 제목");
        updates.update(owner, space, item, edit, false);
      }
      case "REQUEST" -> {
        UUID request = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO change_request(id,space_id,submitted_by_membership_id,submitted_at) VALUES (?,?,?,now())",
            request,
            space,
            memberMembership);
        jdbc.update(
            "INSERT INTO change_request_entry(id,space_id,request_id,ordinal_no,source_type,action,target_item_id,expected_item_version,request_document_id) VALUES (?,?,?,0,'DIRECT_INPUT','UPDATE_ITEM',?,1,?)",
            UUID.randomUUID(),
            space,
            request,
            item,
            newDocument("CHANGE_REQUEST"));
      }
      case "ANALYSIS" -> {
        var source =
            records.create(
                owner, space, new CreateSourceRecordRequest("새 분석 원문", UUID.randomUUID()));
        before = preview(owner);
        body = command(before);
        var work =
            execution
                .claim(space, source.analysis().analysisId(), Duration.ofMinutes(2))
                .orElseThrow();
        execution.complete(work, new AiAnalysisResponse(work.request().requestId(), List.of()));
      }
      default -> throw new IllegalArgumentException();
    }
    assertThat(preview(owner).path("spaceVersion")).isEqualTo(before.path("spaceVersion"));
    if (Set.of("INVITATION", "SHARE", "BASIS", "ANALYSIS").contains(mutation))
      assertThat(preview(owner).path("counts")).isEqualTo(before.path("counts"));
    postError(owner, body, 409, "DELETION_IMPACT_CHANGED");
    assertThat(counts(space).get("care_space")).isEqualTo(1);
    delete(owner, command(preview(owner)));
    assertAllGone();
  }

  @Test
  void leaseDeadlineRenewalAloneDoesNotChangeTheDeletionScope() throws Exception {
    UUID item = createItem();
    locks.acquire(owner, space, item, 1);
    var before = preview(owner);
    jdbc.update(
        "UPDATE care_item_edit_lock SET expires_at=expires_at+interval '1 minute' WHERE item_id=?",
        item);
    assertThat(preview(owner)).isEqualTo(before);
    delete(owner, command(before));
    assertAllGone();
  }

  /** Both execution contracts discard late results and cannot revive removed analysis metadata. */
  @ParameterizedTest
  @ValueSource(strings = {"v1", "v2"})
  void lateAnalysisResultsHeartbeatAndRecoveryCannotRestoreDeletedServiceData(String contract)
      throws Exception {
    var work = running(contract);
    delete(owner, command(preview(owner)));
    boolean stored =
        contract.equals("v1")
            ? execution.complete(
                work, new AiAnalysisResponse(work.request().requestId(), List.of()))
            : execution.completeV2(
                work, new AiV2AnalysisResponse(work.v2Request().requestId(), List.of()));
    assertThat(stored).isFalse();
    assertThat(runs.heartbeat(work.run().id(), work.run().workerToken(), Duration.ofMinutes(2)))
        .isFalse();
    execution.recover(space, work.run().id());
    execution.fail(
        work, com.hanmaum.backend.analysis.code.AnalysisFailureCode.AI_SERVICE_UNAVAILABLE);
    assertThat(execution.claim(space, work.run().id(), Duration.ofMinutes(2))).isEmpty();
    assertAllGone();
  }

  @Test
  void aFailureAtTheFinalRemovalRollsBackAllPreviouslyRemovedRowsAndTheReceipt() throws Exception {
    populateGraph();
    var body = command(preview(owner));
    var before = counts(space);
    String name = "test_delete_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "CREATE FUNCTION "
            + name
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic-space-deletion-failure'; END $$");
    jdbc.execute(
        "CREATE TRIGGER "
            + name
            + " BEFORE DELETE ON care_space FOR EACH ROW WHEN (OLD.id='"
            + space
            + "'::uuid) EXECUTE FUNCTION "
            + name
            + "()");
    try {
      assertThatThrownBy(() -> deletions.delete(owner, space, body))
          .hasStackTraceContaining("synthetic-space-deletion-failure");
      assertThat(counts(space)).isEqualTo(before);
      assertThat(
              jdbc.queryForObject(
                  "SELECT lifecycle FROM care_space WHERE id=?", String.class, space))
          .isEqualTo("ACTIVE");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM command_receipt WHERE actor_user_id=? AND client_request_id=?",
                  Long.class,
                  owner,
                  UUID.fromString(body.path("clientRequestId").asText())))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM content_document WHERE space_id=? AND body IS NOT NULL",
                  Long.class,
                  space))
          .isPositive();
    } finally {
      jdbc.execute("DROP TRIGGER " + name + " ON care_space");
      jdbc.execute("DROP FUNCTION " + name + "()");
    }
    delete(owner, body);
    assertAllGone();
  }

  /** Same and distinct request IDs allow one successful removal, with no second execution. */
  @Test
  void simultaneousSameAndDifferentRequestsNeverDeleteTwiceOrRecreateTheSpace() throws Exception {
    for (boolean same : List.of(true, false)) {
      if (!same) {
        var fresh = spaces.create(owner, new CreateCareSpaceRequest("다음 삭제 공간", UUID.randomUUID()));
        space = fresh.spaceId();
        ownerMembership = fresh.membershipId();
      }
      var body = command(preview(owner));
      var other = body.deepCopy();
      if (!same) other.put("clientRequestId", UUID.randomUUID().toString());
      try (var pool = Executors.newFixedThreadPool(2)) {
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Object>>();
        for (var request : List.of(body, other))
          futures.add(
              pool.submit(
                  () -> {
                    start.await();
                    try {
                      return deletions.delete(owner, space, request);
                    } catch (ApiException ex) {
                      return ex.code().code();
                    }
                  }));
        start.countDown();
        var results =
            List.of(
                futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
        assertThat(results).filteredOn(v -> v instanceof DeleteCareSpaceResponse).hasSize(1);
        for (Object result : results)
          if (result instanceof String error)
            assertThat(error).isIn("REQUEST_IN_PROGRESS", "RESOURCE_NOT_FOUND");
      }
      assertAllGone();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM command_receipt WHERE command_kind='DELETE_CARE_SPACE' AND scope_key=?",
                  Long.class,
                  "SPACE:" + space))
          .isEqualTo(1);
    }
  }

  /** Observes the real DB lock before committing a competing source or ownership change. */
  @ParameterizedTest
  @ValueSource(strings = {"SOURCE", "OWNERSHIP"})
  void concurrentScopeOrOwnerChangeIsRecheckedAfterTheRealSpaceLock(String mutation)
      throws Exception {
    var body = command(preview(owner));
    var locked = new CompletableFuture<Integer>();
    var entered = new CompletableFuture<Integer>();
    var release = new CountDownLatch(1);
    var database = dataSource.unwrap(HikariDataSource.class);
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
                            await(release);
                            if (mutation.equals("SOURCE"))
                              records.create(
                                  owner,
                                  space,
                                  new CreateSourceRecordRequest("경합 중 새 소식", UUID.randomUUID()));
                            else
                              memberships.transfer(
                                  owner,
                                  space,
                                  new TransferOwnershipRequest(
                                      memberMembership, "1", UUID.randomUUID()));
                            return true;
                          }));
      int blocker = locked.get(5, TimeUnit.SECONDS);
      var removal =
          pool.submit(
              () -> {
                try {
                  return new TransactionTemplate(transactions)
                      .execute(
                          tx -> {
                            entered.complete(
                                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            deletions.delete(owner, space, body);
                            return "SUCCESS";
                          });
                } catch (ApiException ex) {
                  return ex.code().code();
                }
              });
      try {
        assertBlocked(monitor, entered.get(5, TimeUnit.SECONDS), blocker, removal);
      } finally {
        release.countDown();
      }
      assertThat(change.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(removal.get(10, TimeUnit.SECONDS))
          .isEqualTo(mutation.equals("SOURCE") ? "DELETION_IMPACT_CHANGED" : "FORBIDDEN");
    } finally {
      release.countDown();
    }
    assertThat(counts(space).get("care_space")).isEqualTo(1);
  }

  @Test
  void completionWaitingBehindADeletionTransactionDiscardsItsLateResponse() throws Exception {
    var work = running("v1");
    var body = command(preview(owner));
    var locked = new CompletableFuture<Integer>();
    var entered = new CompletableFuture<Integer>();
    var release = new CountDownLatch(1);
    var database = dataSource.unwrap(HikariDataSource.class);
    try (var monitor =
            DriverManager.getConnection(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
        var pool = Executors.newFixedThreadPool(2)) {
      var removal =
          pool.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          tx -> {
                            deletions.delete(owner, space, body);
                            locked.complete(
                                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            await(release);
                            return true;
                          }));
      int blocker = locked.get(5, TimeUnit.SECONDS);
      var completion =
          pool.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          tx -> {
                            entered.complete(
                                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            return execution.complete(
                                work,
                                new AiAnalysisResponse(work.request().requestId(), List.of()));
                          }));
      try {
        assertBlocked(monitor, entered.get(5, TimeUnit.SECONDS), blocker, completion);
      } finally {
        release.countDown();
      }
      assertThat(removal.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(completion.get(10, TimeUnit.SECONDS)).isFalse();
    } finally {
      release.countDown();
    }
    assertAllGone();
  }

  /** Keeps typed success responses and strict input consistent with session/CSRF enforcement. */
  @Test
  void swaggerDescribesBothTypedSuccessesAndRequiresSessionAndCsrfTogetherForDeletion()
      throws Exception {
    var result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
    var api = mapper.readTree(result.getResponse().getContentAsString());
    var post = api.path("paths").path("/api/spaces/{spaceId}/deletions").path("post");
    var get = api.path("paths").path("/api/spaces/{spaceId}/deletion-impact").path("get");
    for (var operation : List.of(post, get))
      assertThat(operation.at("/responses/200/content/application~1json/schema").isMissingNode())
          .isFalse();
    assertThat(post.path("security").size()).isEqualTo(1);
    assertThat(post.at("/security/0").propertyNames())
        .containsExactlyInAnyOrder("SessionCookie", "CsrfToken");
    assertThat(get.at("/security/0").propertyNames()).containsExactly("SessionCookie");
    var schema = api.at("/components/schemas/CareSpaceDeletionBody");
    assertThat(schema.path("required"))
        .extracting(JsonNode::asText)
        .containsExactlyInAnyOrder("expectedSpaceVersion", "impactToken", "clientRequestId");
    assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    assertThat(api.at("/components/responses/DELETE_CARE_SPACE_CONFLICT").toString())
        .contains(
            "VERSION_CONFLICT",
            "DELETION_IMPACT_CHANGED",
            "IDEMPOTENCY_KEY_REUSED",
            "REQUEST_IN_PROGRESS");
  }

  /**
   * Populates every space-scoped table, including the invitation/member FK cycle and old results.
   */
  private Graph populateGraph() {
    var source =
        records.create(owner, space, new CreateSourceRecordRequest("삭제할 합성 원문", UUID.randomUUID()));
    var work =
        execution.claim(space, source.analysis().analysisId(), Duration.ofMinutes(2)).orElseThrow();
    var suggestion =
        new AiAnalysisResponse.Suggestion(
            AiAnalysisResponse.Operation.CREATE,
            CareItemType.OBSERVATION,
            null,
            null,
            List.of(),
            "삭제할 합성 제안",
            "삭제할 합성 원문",
            Map.of("content", "삭제할 합성 원문"));
    assertThat(
            execution.complete(
                work, new AiAnalysisResponse(work.request().requestId(), List.of(suggestion))))
        .isTrue();
    UUID proposal =
        jdbc.queryForObject(
            "SELECT id FROM ai_proposal WHERE run_id=?", UUID.class, work.run().id());
    UUID evidence =
        jdbc.queryForObject(
            "SELECT primary_evidence_id FROM ai_proposal WHERE id=?", UUID.class, proposal);
    UUID item = createItem(),
        doc = document(item),
        basis =
            jdbc.queryForObject("SELECT id FROM care_item_basis WHERE item_id=?", UUID.class, item);
    jdbc.update("UPDATE care_item SET version=2 WHERE id=?", item);
    jdbc.update(
        "INSERT INTO care_item_history(space_id,item_id,version,operation,document_id,status,changed_by_membership_id,changed_at) VALUES (?,?,2,'UPDATE',?,'SCHEDULED',?,now())",
        space,
        item,
        doc,
        ownerMembership);
    jdbc.update(
        "INSERT INTO analysis_input_item(space_id,run_id,item_id,item_version,document_id,source_evidence_ids,item_type,item_status) VALUES (?,?,?,2,?,ARRAY[]::uuid[],'SCHEDULE','SCHEDULED')",
        space,
        work.run().id(),
        item,
        doc);
    jdbc.update(
        "INSERT INTO analysis_input_basis(space_id,run_id,item_id,basis_id,direct_document_id) VALUES (?,?,?,?,?)",
        space,
        work.run().id(),
        item,
        basis,
        doc);
    jdbc.update(
        "INSERT INTO care_item_document_parent(space_id,document_id,parent_document_id) VALUES (?,?,?)",
        space,
        doc,
        newDocument("DIRECT_INPUT"));
    jdbc.update(
        "INSERT INTO care_item_document_evidence(space_id,document_id,evidence_id) VALUES (?,?,?)",
        space,
        doc,
        evidence);
    UUID decision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO proposal_decision_batch(id,space_id,decided_by_membership_id,decision,decided_at) VALUES (?,?,?,'APPLIED',now())",
        decision,
        space,
        ownerMembership);
    jdbc.update(
        "INSERT INTO proposal_decision(proposal_id,space_id,batch_id,ordinal_no,item_id,item_version,document_id) VALUES (?,?,?,0,?,1,?)",
        proposal,
        space,
        decision,
        item,
        doc);
    jdbc.update("UPDATE ai_proposal SET resolution='APPLIED' WHERE id=?", proposal);
    UUID request = UUID.randomUUID(), batch = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO change_request(id,space_id,submitted_by_membership_id,submitted_at) VALUES (?,?,?,now())",
        request,
        space,
        memberMembership);
    jdbc.update(
        "INSERT INTO change_request_decision_batch(id,space_id,decided_by_membership_id,decision,decided_at) VALUES (?,?,?,'APPLIED',now())",
        batch,
        space,
        ownerMembership);
    jdbc.update(
        "INSERT INTO change_request_entry(id,space_id,request_id,ordinal_no,source_type,action,target_item_id,expected_item_version,request_document_id,status,decision_batch_id,decision_ordinal_no,result_item_id,result_item_version,result_document_id) VALUES (?,?,?,0,'DIRECT_INPUT','UPDATE_ITEM',?,1,?,'APPLIED',?,0,?,2,?)",
        UUID.randomUUID(),
        space,
        request,
        item,
        newDocument("CHANGE_REQUEST"),
        batch,
        item,
        doc);
    jdbc.update(
        "INSERT INTO change_request_entry(id,space_id,request_id,ordinal_no,source_type,action,expected_space_version,request_document_id) VALUES (?,?,?,1,'DIRECT_INPUT','UPDATE_SPACE',1,?)",
        UUID.randomUUID(),
        space,
        request,
        newDocument("CHANGE_REQUEST"));
    String leaseToken = locks.acquire(owner, space, item, 2).leaseToken();
    var invitation = invitations.issue(owner, space, UUID.randomUUID());
    String invitationToken = tail(invitation.invitationUrl());
    invitations.join(user(), invitationToken, UUID.randomUUID());
    var shared = sharing.create(owner, space, shareRequest(item, "2"));
    return new Graph(item, tail(shared.shareUrl()), invitationToken, leaseToken);
  }

  private AnalysisExecutionService.Work running(String contract) {
    var source =
        records.create(
            owner, space, new CreateSourceRecordRequest("실행 중 합성 원문", UUID.randomUUID()));
    jdbc.update(
        "UPDATE analysis_run SET contract_version=? WHERE id=?",
        contract,
        source.analysis().analysisId());
    return execution
        .claim(space, source.analysis().analysisId(), Duration.ofMinutes(2))
        .orElseThrow();
  }

  private UUID createItem() {
    return items
        .create(
            owner,
            space,
            new CreateCareItemRequest(
                "SCHEDULE",
                "삭제할 합성 제목",
                "삭제할 합성 설명",
                new ScheduleFields(null, null, null),
                UUID.randomUUID()))
        .itemId();
  }

  private UUID document(UUID item) {
    return jdbc.queryForObject(
        "SELECT current_document_id FROM care_item WHERE id=?", UUID.class, item);
  }

  private UUID newDocument(String kind) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO content_document(id,space_id,kind,body,created_at) VALUES (?,?,?,'{\"values\":{\"title\":\"삭제할 합성 요청\"}}',now())",
        id,
        space,
        kind);
    return id;
  }

  private ObjectNode shareRequest(UUID item, String version) {
    var body = mapper.createObjectNode().put("clientRequestId", UUID.randomUUID().toString());
    body.putArray("targets")
        .addObject()
        .put("itemId", item.toString())
        .put("expectedItemVersion", version)
        .put("includesChange", false);
    return body;
  }

  private ObjectNode command(JsonNode preview) {
    return mapper
        .createObjectNode()
        .put("expectedSpaceVersion", preview.path("spaceVersion").asText())
        .put("impactToken", preview.path("impactToken").asText())
        .put("clientRequestId", UUID.randomUUID().toString());
  }

  private JsonNode preview(UUID actor) throws Exception {
    var result =
        mvc.perform(get(base() + "/deletion-impact").with(authentication(login(actor))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString()).path("data");
  }

  private JsonNode delete(UUID actor, JsonNode body) throws Exception {
    var result =
        mvc.perform(
                post(base() + "/deletions")
                    .with(authentication(login(actor)))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString()).path("data");
  }

  private void postError(UUID actor, JsonNode body, int status, String code) throws Exception {
    mvc.perform(
            post(base() + "/deletions")
                .with(authentication(login(actor)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().is(status))
        .andExpect(jsonPath("$.code").value(code));
  }

  private String base() {
    return "/api/spaces/" + space;
  }

  private String tail(String url) {
    return url.substring(url.lastIndexOf('/') + 1);
  }

  /** Discovers all space-scoped tables so forgotten purge branches fail the integration checks. */
  private Map<String, Long> counts(UUID space) {
    var counts = new TreeMap<String, Long>();
    for (String table :
        jdbc.queryForList(
            "SELECT table_name FROM information_schema.columns WHERE table_schema=current_schema() AND column_name='space_id'",
            String.class))
      counts.put(
          table,
          jdbc.queryForObject(
              "SELECT count(*) FROM \"" + table.replace("\"", "\"\"") + "\" WHERE space_id=?",
              Long.class,
              space));
    counts.put(
        "care_space",
        jdbc.queryForObject("SELECT count(*) FROM care_space WHERE id=?", Long.class, space));
    return counts;
  }

  private void assertAllGone() {
    assertThat(counts(space)).allSatisfy((table, count) -> assertThat(count).as(table).isZero());
  }

  private void assertCode(Runnable action, String code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.code().code()).isEqualTo(code));
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

  /** Bounds test synchronization and preserves interruption rather than leaving blocked workers. */
  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(ex);
    }
  }

  /** Requires PostgreSQL to identify the blocker, avoiding scheduling-only timeout false passes. */
  private void assertBlocked(Connection monitor, int waiter, int blocker, Future<?> pending)
      throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    try (var query =
        monitor.prepareStatement(
            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock' AND ?=ANY(pg_blocking_pids(pid)))")) {
      query.setInt(1, waiter);
      query.setInt(2, blocker);
      while (System.nanoTime() < end && !pending.isDone()) {
        try (var result = query.executeQuery()) {
          if (result.next() && result.getBoolean(1)) {
            assertThat(pending.isDone()).isFalse();
            return;
          }
        }
        Thread.sleep(20);
      }
    }
    fail("Expected an actual PostgreSQL wait on the held space transaction");
  }

  private record Graph(UUID item, String shareToken, String invitationToken, String leaseToken) {}
}
