package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanmaum.backend.carespace.code.CareSpaceErrorCode;
import com.hanmaum.backend.carespace.dto.CareSpaceRole;
import com.hanmaum.backend.carespace.dto.CreateCareSpaceRequest;
import com.hanmaum.backend.carespace.dto.CreateCareSpaceResponse;
import com.hanmaum.backend.carespace.repository.SpaceMembershipRepository;
import com.hanmaum.backend.carespace.service.CareSpaceAccessService;
import com.hanmaum.backend.carespace.service.CareSpaceCreationService;
import com.hanmaum.backend.carespace.service.CareSpaceQueryService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.code.ErrorCode;
import com.hanmaum.backend.global.command.CommandReceiptRepository;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.security.oauth.MemberOAuth2User;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=4")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CareSpaceIntegrationTests {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired CareSpaceCreationService creation;
  @Autowired CareSpaceQueryService queries;
  @Autowired CareSpaceAccessService access;
  @Autowired PlatformTransactionManager transactionManager;
  @MockitoSpyBean SpaceMembershipRepository memberships;
  @MockitoSpyBean CommandReceiptRepository receipts;
  SpaceMembershipRepository membershipSpy;
  CommandReceiptRepository receiptSpy;
  UUID actor;

  @BeforeEach
  void setUp() {
    actor = newUser();
    membershipSpy = AopTestUtils.getUltimateTargetObject(memberships);
    receiptSpy = AopTestUtils.getUltimateTargetObject(receipts);
  }

  @Test
  void createsAtomicOwnerMembershipAndReturnsOnlyThePublicContract() throws Exception {
    UUID key = UUID.randomUUID();
    var response =
        postCreate(actor, "　 엄마  아빠 \u00a0", key)
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.message").value("요청이 완료되었습니다."))
            .andExpect(jsonPath("$.errors").isEmpty())
            .andReturn()
            .getResponse();
    var body = mapper.readTree(response.getContentAsString());
    assertThat(body.size()).isEqualTo(6);
    assertThat(body.get("data").size()).isEqualTo(2);
    UUID spaceId = UUID.fromString(body.at("/data/spaceId").asText());
    UUID membershipId = UUID.fromString(body.at("/data/membershipId").asText());
    assertThat(response.getHeader("Location")).isEqualTo("/api/spaces/" + spaceId);
    var detail = queries.detail(actor, spaceId);
    assertThat(detail.subjectLabel()).isEqualTo("엄마  아빠");
    assertThat(detail.membershipId()).isEqualTo(membershipId);
    assertThat(detail.role()).isEqualTo(CareSpaceRole.OWNER);
    assertThat(detail.memberCount()).isEqualTo(1);
    assertThat(detail.version()).isEqualTo("1");
    assertThat(detail.createdAt()).isEqualTo(detail.updatedAt());
    assertThat(
            jdbc.queryForObject(
                "SELECT owner_user_id FROM care_space WHERE id = ?", UUID.class, spaceId))
        .isEqualTo(actor);
    var receipt =
        jdbc.queryForMap(
            "SELECT request_digest, result_refs::text, status FROM command_receipt WHERE actor_user_id = ?",
            actor);
    assertThat(receipt.get("request_digest").toString())
        .matches("[0-9a-f]{64}")
        .doesNotContain("엄마");
    assertThat(mapper.readTree(receipt.get("result_refs").toString()).size()).isEqualTo(2);
    assertThat(receipt.get("status")).isEqualTo("SUCCEEDED");
    mvc.perform(get("/api/spaces/{id}", spaceId).with(authentication(login(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value("1"))
        .andExpect(jsonPath("$.data.createdAt").isString())
        .andExpect(jsonPath("$.data.role").value("OWNER"));
  }

  static Stream<String> invalidLabels() {
    return Stream.of(
        "",
        " ",
        "\u00a0　",
        "엄마\n",
        "\r엄마",
        "엄마\u0085아빠",
        "엄마\u2028아빠",
        "엄마\u2029아빠",
        "a".repeat(31),
        "😀".repeat(31),
        "엄마\0",
        "엄마\u000b아빠");
  }

  @ParameterizedTest
  @MethodSource("invalidLabels")
  void rejectsInvalidLabelsWithoutAnyWrites(String label) throws Exception {
    postCreate(actor, label, UUID.randomUUID())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.errors[0].field").value("subjectLabel"));
    assertNoCreation(actor);
  }

  @Test
  void countsUnicodeCodePointsAndDoesNotNormalizeCaseOrInnerSpaces() {
    var emoji = create(actor, "😀".repeat(30));
    assertThat(queries.detail(actor, emoji.spaceId()).subjectLabel()).isEqualTo("😀".repeat(30));
    create(actor, "Mom");
    create(actor, "mom");
    create(actor, "엄 마");
    create(actor, "엄  마");
    assertThat(queries.list(actor).items()).hasSize(5);
  }

  @Test
  void rejectsMissingFieldsAndMalformedUuids() throws Exception {
    for (String body :
        List.of(
            "{}",
            "{\"subjectLabel\":null,\"clientRequestId\":\"" + UUID.randomUUID() + "\"}",
            "{\"subjectLabel\":\"엄마\"}",
            "{\"subjectLabel\":\"엄마\",\"clientRequestId\":\"bad\"}")) {
      mvc.perform(
              post("/api/spaces")
                  .with(authentication(login(actor)))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    mvc.perform(get("/api/spaces/not-a-uuid").with(authentication(login(actor))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("spaceId"));
    assertNoCreation(actor);
  }

  @Test
  void preservesSessionCsrfAndCurrentMemberChecks() throws Exception {
    mvc.perform(get("/api/spaces")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/spaces/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    String body = mapper.writeValueAsString(new CreateCareSpaceRequest("엄마", UUID.randomUUID()));
    mvc.perform(
            post("/api/spaces").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mvc.perform(
            post("/api/spaces")
                .with(authentication(login(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(get("/api/spaces").with(user("not-a-member-principal")))
        .andExpect(status().isUnauthorized());
    jdbc.update("DELETE FROM social_account WHERE user_id = ?", actor);
    mvc.perform(get("/api/spaces").with(authentication(login(actor))))
        .andExpect(status().isUnauthorized());
    assertNoCreation(actor);
  }

  @Test
  void rejectsNonStringLabelsAndEscapedUnpairedSurrogates() throws Exception {
    for (String label : List.of("123", "true", "[]", "{}", "\"\\ud800\"")) {
      mvc.perform(
              post("/api/spaces")
                  .with(authentication(login(actor)))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"subjectLabel\":"
                          + label
                          + ",\"clientRequestId\":\""
                          + UUID.randomUUID()
                          + "\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    assertNoCreation(actor);
  }

  @Test
  void ignoresForgedActorFieldsAndReturnsEmptyListForNewMember() throws Exception {
    mvc.perform(get("/api/spaces").with(authentication(login(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items").isEmpty());
    UUID other = newUser();
    mvc.perform(
            post("/api/spaces")
                .with(authentication(login(actor)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "subjectLabel",
                            "엄마",
                            "clientRequestId",
                            UUID.randomUUID(),
                            "userId",
                            other,
                            "ownerUserId",
                            other))))
        .andExpect(status().isCreated());
    assertThat(queries.list(actor).items()).hasSize(1);
    assertThat(queries.list(other).items()).isEmpty();
  }

  @Test
  void listsOnlyActiveParticipationWithStableOrderingAndCurrentCounts() {
    UUID owner = newUser();
    var first = create(owner, "첫째");
    var second = create(owner, "둘째");
    var deleted = create(owner, "삭제 중");
    var ended = create(owner, "종료");
    Instant joined = Instant.parse("2026-10-01T00:00:00Z");
    UUID firstMembership = join(actor, first.spaceId(), joined);
    join(actor, second.spaceId(), joined);
    join(actor, deleted.spaceId(), joined);
    UUID endedMembership = join(actor, ended.spaceId(), joined);
    end(endedMembership);
    UUID departed = join(newUser(), first.spaceId(), joined);
    end(departed);
    jdbc.update("UPDATE space_membership SET user_id = NULL WHERE id = ?", departed);
    jdbc.update("UPDATE care_space SET lifecycle = 'DELETING' WHERE id = ?", deleted.spaceId());
    var solo = create(actor, "혼자");
    var list = queries.list(actor).items();
    var ordered =
        Stream.of(first.spaceId(), second.spaceId())
            .sorted(java.util.Comparator.comparing(UUID::toString))
            .toList();
    assertThat(list)
        .extracting(item -> item.spaceId())
        .containsExactly(ordered.get(0), ordered.get(1), solo.spaceId());
    var firstDetail = queries.detail(actor, first.spaceId());
    assertThat(firstDetail.role()).isEqualTo(CareSpaceRole.MEMBER);
    assertThat(firstDetail.membershipId()).isEqualTo(firstMembership);
    assertThat(firstDetail.memberCount()).isEqualTo(2);
    assertThat(queries.list(owner).items()).hasSize(3);
  }

  @Test
  void deniesNonMembersEndedMembershipAndDeletingSpaces() throws Exception {
    var space = create(newUser(), "엄마");
    mvc.perform(get("/api/spaces/{id}", space.spaceId()).with(authentication(login(actor))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    UUID membership = join(actor, space.spaceId(), Instant.now());
    end(membership);
    assertCode(() -> queries.detail(actor, space.spaceId()), CommonErrorCode.FORBIDDEN);
    mvc.perform(get("/api/spaces/{id}", UUID.randomUUID()).with(authentication(login(actor))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    jdbc.update("UPDATE care_space SET lifecycle = 'DELETING' WHERE id = ?", space.spaceId());
    assertCode(() -> queries.detail(actor, space.spaceId()), CommonErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void replaysNormalizedSuccessfulRequestWithoutNewRowsOrTimestampChanges() throws Exception {
    UUID key = UUID.randomUUID();
    var first = creation.create(actor, new CreateCareSpaceRequest("엄마", key));
    var before = queries.detail(actor, first.spaceId());
    postCreate(actor, "　엄마 ", key)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.spaceId").value(first.spaceId().toString()))
        .andExpect(jsonPath("$.data.membershipId").value(first.membershipId().toString()));
    assertThat(queries.detail(actor, first.spaceId())).isEqualTo(before);
    assertThat(count("care_space", "owner_user_id", actor)).isEqualTo(1);
    assertThat(count("space_membership", "user_id", actor)).isEqualTo(1);
    assertThat(count("command_receipt", "actor_user_id", actor)).isEqualTo(1);
    postCreate(actor, "아빠", key)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    postCreate(actor, "엄마", UUID.randomUUID())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SPACE_LABEL_DUPLICATED"));
  }

  @Test
  void scopesRequestIdsToTheActorButRejectsDifferentCommandsAndScopes() {
    UUID key = UUID.randomUUID();
    creation.create(actor, new CreateCareSpaceRequest("엄마", key));
    creation.create(newUser(), new CreateCareSpaceRequest("엄마", key));
    jdbc.update(
        "UPDATE command_receipt SET command_kind = 'ANOTHER_COMMAND' WHERE actor_user_id = ?",
        actor);
    assertCode(
        () -> creation.create(actor, new CreateCareSpaceRequest("엄마", key)),
        CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    jdbc.update(
        "UPDATE command_receipt SET command_kind = 'CREATE_CARE_SPACE', scope_key = 'ANOTHER_SCOPE' WHERE actor_user_id = ?",
        actor);
    assertCode(
        () -> creation.create(actor, new CreateCareSpaceRequest("엄마", key)),
        CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
  }

  @Test
  void duplicateLabelScopeFollowsCurrentOwnerRatherThanCreatorOrParticipant() {
    var space = create(actor, "엄마");
    UUID nextOwner = newUser();
    UUID nextMembership = join(nextOwner, space.spaceId(), Instant.now());
    transfer(space.spaceId(), nextOwner, nextMembership);
    create(actor, "엄마");
    assertCode(() -> create(nextOwner, "엄마"), CareSpaceErrorCode.SPACE_LABEL_DUPLICATED);
    assertThat(queries.detail(actor, space.spaceId()).role()).isEqualTo(CareSpaceRole.MEMBER);
    assertThat(queries.list(actor).items()).hasSize(2);
  }

  @Test
  void replayRechecksCurrentMembershipAndDeletedTargetWithoutRecreating() {
    UUID key = UUID.randomUUID();
    var space = creation.create(actor, new CreateCareSpaceRequest("엄마", key));
    UUID nextOwner = newUser();
    UUID nextMembership = join(nextOwner, space.spaceId(), Instant.now());
    transfer(space.spaceId(), nextOwner, nextMembership);
    assertThat(creation.create(actor, new CreateCareSpaceRequest("엄마", key))).isEqualTo(space);
    end(space.membershipId());
    assertCode(
        () -> creation.create(actor, new CreateCareSpaceRequest("엄마", key)),
        CommonErrorCode.FORBIDDEN);
    join(actor, space.spaceId(), Instant.now());
    assertCode(
        () -> creation.create(actor, new CreateCareSpaceRequest("엄마", key)),
        CommonErrorCode.FORBIDDEN);
    jdbc.update("UPDATE care_space SET lifecycle = 'DELETING' WHERE id = ?", space.spaceId());
    assertCode(
        () -> creation.create(actor, new CreateCareSpaceRequest("엄마", key)),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              jdbc.update("DELETE FROM space_membership WHERE space_id = ?", space.spaceId());
              jdbc.update("DELETE FROM care_space WHERE id = ?", space.spaceId());
            });
    assertCode(
        () -> creation.create(actor, new CreateCareSpaceRequest("엄마", key)),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    assertThat(count("command_receipt", "actor_user_id", actor)).isEqualTo(1);
  }

  @Test
  void rollsBackAllWritesAndAllowsRetryWhenReceiptPersistenceFails() throws Exception {
    UUID key = UUID.randomUUID();
    doThrow(new DataAccessResourceFailureException("synthetic failure"))
        .when(receiptSpy)
        .saveSuccess(any(), any(), any(), any());
    postCreate(actor, "엄마", key)
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    assertNoCreation(actor);
    doCallRealMethod().when(receiptSpy).saveSuccess(any(), any(), any(), any());
    postCreate(actor, "엄마", key).andExpect(status().isCreated());
  }

  @Test
  void rollsBackSpaceWhenInitialMembershipFails() {
    doThrow(new DataAccessResourceFailureException("synthetic failure"))
        .when(membershipSpy)
        .insert(any());
    assertThatThrownBy(() -> create(actor, "엄마")).isInstanceOf(DataAccessException.class);
    assertNoCreation(actor);
  }

  @Test
  void concurrentSameKeyGetsInProgressThenReplaysCommittedResult() throws Exception {
    UUID key = UUID.randomUUID();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              entered.countDown();
              if (!release.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("test timeout");
              return invocation.callRealMethod();
            })
        .when(membershipSpy)
        .insert(any());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(() -> creation.create(actor, new CreateCareSpaceRequest("엄마", key)));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        postCreate(actor, "엄마", key)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));
      } finally {
        release.countDown();
      }
      var result = first.get(10, TimeUnit.SECONDS);
      assertThat(creation.create(actor, new CreateCareSpaceRequest("엄마", key))).isEqualTo(result);
      assertThat(count("care_space", "owner_user_id", actor)).isEqualTo(1);
    }
  }

  @Test
  void concurrentDifferentKeysCannotCreateDuplicateOwnedLabels() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> concurrentCreate(start));
      var second = executor.submit(() -> concurrentCreate(start));
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("SUCCESS", "SPACE_LABEL_DUPLICATED");
    }
    assertThat(count("care_space", "owner_user_id", actor)).isEqualTo(1);
    assertThat(count("space_membership", "user_id", actor)).isEqualTo(1);
    assertThat(count("command_receipt", "actor_user_id", actor)).isEqualTo(1);
  }

  @Test
  void databaseRejectsOwnerOutsideSpaceWrongUserAndEndedOwner() {
    var first = create(actor, "엄마");
    var second = create(newUser(), "아빠");
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status ->
                            jdbc.update(
                                "UPDATE care_space SET owner_membership_id = ? WHERE id = ?",
                                second.membershipId(),
                                first.spaceId())))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("care_space_active_owner_fk");
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status ->
                            jdbc.update(
                                "UPDATE care_space SET owner_user_id = ? WHERE id = ?",
                                newUser(),
                                first.spaceId())))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("care_space_active_owner_fk");
    assertThatThrownBy(() -> end(first.membershipId()))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("care_space_active_owner_fk");
    assertThat(queries.detail(actor, first.spaceId()).role()).isEqualTo(CareSpaceRole.OWNER);
  }

  @Test
  void databaseRejectsOwnerlessSpaceAndDuplicateCurrentParticipation() {
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status ->
                            jdbc.update(
                                """
            INSERT INTO care_space (id, owner_membership_id, owner_user_id, subject_label, created_at, updated_at)
            VALUES (?, ?, ?, '엄마', now(), now())
            """,
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                actor)))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("care_space_active_owner_fk");
    assertNoCreation(actor);
    var space = create(actor, "엄마");
    assertThatThrownBy(() -> join(actor, space.spaceId(), Instant.now()))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("space_membership_active_user_uq");
  }

  @Test
  void transferCannotViolateNewOwnersLabelUniqueness() {
    UUID nextOwner = newUser();
    var first = create(actor, "엄마");
    create(nextOwner, "엄마");
    UUID nextMembership = join(nextOwner, first.spaceId(), Instant.now());
    assertThatThrownBy(() -> transfer(first.spaceId(), nextOwner, nextMembership))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("care_space_owner_label_uq");
    assertThat(queries.detail(actor, first.spaceId()).role()).isEqualTo(CareSpaceRole.OWNER);
  }

  @Test
  void accessServiceRequiresCallerTransactionAndDistinguishesOwnerFromMember() {
    var space = create(actor, "엄마");
    UUID member = newUser();
    UUID membership = join(member, space.spaceId(), Instant.now());
    assertThatThrownBy(() -> access.requireActiveMember(actor, space.spaceId()))
        .isInstanceOf(IllegalTransactionStateException.class);
    var owner =
        new TransactionTemplate(transactionManager)
            .execute(status -> access.requireOwner(actor, space.spaceId()));
    assertThat(owner.role()).isEqualTo(CareSpaceRole.OWNER);
    var participant =
        new TransactionTemplate(transactionManager)
            .execute(status -> access.requireActiveMember(member, space.spaceId()));
    assertThat(participant.membershipId()).isEqualTo(membership);
    assertCode(
        () ->
            new TransactionTemplate(transactionManager)
                .execute(status -> access.requireOwner(member, space.spaceId())),
        CommonErrorCode.FORBIDDEN);
    end(membership);
    assertCode(
        () ->
            new TransactionTemplate(transactionManager)
                .execute(status -> access.requireActiveMember(member, space.spaceId())),
        CommonErrorCode.FORBIDDEN);
  }

  @Test
  void accessLockPreventsMembershipRevocationBetweenCheckAndMutation() throws Exception {
    var space = create(actor, "엄마");
    UUID member = newUser();
    UUID membership = join(member, space.spaceId(), Instant.now());
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var holding =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          status -> {
                            access.requireActiveMember(member, space.spaceId());
                            locked.countDown();
                            await(release);
                          }));
      try {
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(
                () ->
                    new TransactionTemplate(transactionManager)
                        .executeWithoutResult(
                            status -> {
                              jdbc.execute("SET LOCAL lock_timeout = '250ms'");
                              end(membership);
                            }))
            .isInstanceOf(DataAccessException.class);
      } finally {
        release.countDown();
      }
      holding.get(10, TimeUnit.SECONDS);
    }
    end(membership);
    assertCode(() -> queries.detail(member, space.spaceId()), CommonErrorCode.FORBIDDEN);
  }

  @Test
  void openApiDescribesActualSchemasAndSessionAndCsrfTogether() throws Exception {
    var result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
    var doc = mapper.readTree(result.getResponse().getContentAsString());
    var create = doc.at("/paths/~1api~1spaces/post");
    assertThat(create.at("/security").size()).isEqualTo(1);
    assertThat(create.at("/security/0").has("SessionCookie")).isTrue();
    assertThat(create.at("/security/0").has("CsrfToken")).isTrue();
    assertThat(create.path("parameters").toString()).doesNotContain("currentUser", "userId");
    assertThat(create.at("/responses/409/$ref").asText()).endsWith("CREATE_CARE_SPACE_CONFLICT");
    assertThat(
            doc.at(
                    "/components/responses/CREATE_CARE_SPACE_CONFLICT/content/application~1json/examples")
                .size())
        .isEqualTo(3);
    assertThat(doc.at("/components/schemas/CareSpaceDetail/properties/version/type").asText())
        .isEqualTo("string");
    assertThat(doc.at("/paths/~1api~1spaces/get").isMissingNode()).isFalse();
    assertThat(doc.at("/paths/~1api~1spaces~1{spaceId}/get").isMissingNode()).isFalse();
  }

  private UUID newUser() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO app_user (id, display_name, created_at, updated_at) VALUES (?, '테스트 회원', now(), now())",
        id);
    jdbc.update(
        "INSERT INTO social_account (id, user_id, provider, provider_user_id, created_at) VALUES (?, ?, 'KAKAO', ?, now())",
        UUID.randomUUID(),
        id,
        id.toString());
    return id;
  }

  private OAuth2AuthenticationToken login(UUID userId) {
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    var verified = new DefaultOAuth2User(authorities, Map.of("id", userId.toString()), "id");
    return new OAuth2AuthenticationToken(
        new MemberOAuth2User(userId, verified), authorities, "kakao");
  }

  private ResultActions postCreate(UUID userId, String label, UUID key) throws Exception {
    return mvc.perform(
        post("/api/spaces")
            .with(authentication(login(userId)))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(new CreateCareSpaceRequest(label, key))));
  }

  private CreateCareSpaceResponse create(UUID userId, String label) {
    return creation.create(userId, new CreateCareSpaceRequest(label, UUID.randomUUID()));
  }

  private UUID join(UUID userId, UUID spaceId, Instant joined) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO space_membership (id, space_id, user_id, joined_at) VALUES (?, ?, ?, ?)",
        id,
        spaceId,
        userId,
        Timestamp.from(joined));
    return id;
  }

  private void end(UUID membership) {
    jdbc.update(
        "UPDATE space_membership SET ended_at = now(), end_reason = 'LEFT' WHERE id = ?",
        membership);
  }

  private void transfer(UUID space, UUID user, UUID membership) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                jdbc.update(
                    "UPDATE care_space SET owner_membership_id = ?, owner_user_id = ?, version = version + 1, updated_at = now() WHERE id = ?",
                    membership,
                    user,
                    space));
  }

  private long count(String table, String field, UUID value) {
    // Identifiers are test constants, never request input.
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + field + " = ?", Long.class, value);
  }

  private void assertNoCreation(UUID userId) {
    assertThat(count("care_space", "owner_user_id", userId)).isZero();
    assertThat(count("space_membership", "user_id", userId)).isZero();
    assertThat(count("command_receipt", "actor_user_id", userId)).isZero();
  }

  private static void assertCode(Runnable action, ErrorCode code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ApiException.class, exception -> assertThat(exception.code()).isEqualTo(code));
  }

  private String concurrentCreate(CountDownLatch start) {
    await(start);
    try {
      create(actor, "엄마");
      return "SUCCESS";
    } catch (ApiException exception) {
      return exception.code().code();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
