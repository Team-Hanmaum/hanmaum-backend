package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanmaum.backend.carespace.code.CareSpaceErrorCode;
import com.hanmaum.backend.carespace.dto.CareSpaceRole;
import com.hanmaum.backend.carespace.dto.CreateCareSpaceRequest;
import com.hanmaum.backend.carespace.dto.CreateInvitationResponse;
import com.hanmaum.backend.carespace.repository.InvitationRepository;
import com.hanmaum.backend.carespace.service.CareSpaceCreationService;
import com.hanmaum.backend.carespace.service.InvitationService;
import com.hanmaum.backend.carespace.service.SpaceMemberQueryService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.code.ErrorCode;
import com.hanmaum.backend.global.command.CommandReceiptRepository;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.security.oauth.MemberOAuth2User;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=4")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InvitationIntegrationTests {
  private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired CareSpaceCreationService creation;
  @Autowired InvitationService invitations;
  @Autowired SpaceMemberQueryService members;
  @Autowired PlatformTransactionManager transactionManager;
  @MockitoBean Clock clock;
  @MockitoSpyBean CommandReceiptRepository receipts;
  @MockitoSpyBean InvitationRepository invitationRepository;
  CommandReceiptRepository receiptSpy;
  UUID owner;
  UUID space;

  @BeforeEach
  void setUp() {
    when(clock.instant()).thenReturn(NOW);
    receiptSpy = AopTestUtils.getUltimateTargetObject(receipts);
    owner = newUser("소유자");
    space = creation.create(owner, new CreateCareSpaceRequest("엄마", UUID.randomUUID())).spaceId();
  }

  @Test
  void issuesMultiUseLinksWithEncryptedUrlsAndReplaysWithoutRenewal() throws Exception {
    UUID key = UUID.randomUUID();
    var response =
        postIssue(owner, space, key)
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andReturn()
            .getResponse();
    var body = mapper.readTree(response.getContentAsString());
    assertThat(body.size()).isEqualTo(6);
    assertThat(body.get("data").size()).isEqualTo(5);
    var invitation = invitations.issue(owner, space, key);
    assertThat(Duration.between(invitation.issuedAt(), invitation.expiresAt()))
        .isEqualTo(Duration.ofHours(24));
    assertThat(invitation.invitationUrl()).startsWith("http://localhost:5173/invitations/");
    var stored =
        jdbc.queryForMap(
            "SELECT token_hash, encrypted_url FROM invitation WHERE id = ?",
            invitation.invitationId());
    assertThat(stored.get("token_hash").toString()).matches("[0-9a-f]{64}");
    assertThat(stored.toString())
        .doesNotContain(token(invitation))
        .doesNotContain(invitation.invitationUrl());
    var receipt =
        jdbc.queryForMap(
            "SELECT scope_key, request_digest, result_refs::text FROM command_receipt WHERE actor_user_id = ? AND client_request_id = ?",
            owner,
            key);
    assertThat(receipt.toString()).doesNotContain(token(invitation));
    assertThat(mapper.readTree(receipt.get("result_refs").toString()).size()).isEqualTo(3);
    when(clock.instant()).thenReturn(invitation.expiresAt());
    assertThat(invitations.issue(owner, space, key)).isEqualTo(invitation);
    assertThat(version()).isEqualTo(1);
    assertThat(count("invitation", "space_id", space)).isEqualTo(1);
  }

  @Test
  void memberCanIssueAndFreshKeysDoNotRevokeOlderLinks() {
    var original = issue(owner);
    UUID member = newUser(null);
    invitations.join(member, token(original), UUID.randomUUID());
    var issuedByMember = issue(member);
    var another = issue(owner);
    assertThat(another.invitationId()).isNotEqualTo(original.invitationId());
    assertThat(invitations.preview(member, token(original)).isMember()).isTrue();
    UUID third = newUser("가족");
    assertThat(invitations.join(third, token(issuedByMember), UUID.randomUUID()).created())
        .isTrue();
    assertThat(invitations.join(newUser("다른 가족"), token(original), UUID.randomUUID()).created())
        .isTrue();
    assertThat(version()).isEqualTo(4);
  }

  @Test
  void previewIsPrivateReadOnlyAndDoesNotJoinTheSpace() throws Exception {
    var invitation = issue(owner);
    UUID outsider = newUser("미참여자");
    var result =
        mvc.perform(
                get("/api/invitations/{token}", token(invitation))
                    .with(authentication(login(outsider))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.isMember").value(false))
            .andReturn()
            .getResponse();
    assertThat(mapper.readTree(result.getContentAsString()).get("data").size()).isEqualTo(4);
    assertThat(result.getContentAsString())
        .doesNotContain("소유자", "엄마", "displayName", "invitationUrl");
    assertThat(invitations.preview(owner, token(invitation)).isMember()).isTrue();
    assertThat(count("space_membership", "user_id", outsider)).isZero();
    assertThat(version()).isEqualTo(1);
  }

  @Test
  void joinsAtomicallyAndPreservesExistingOwnerAndOriginalSuccessStatus() throws Exception {
    var invitation = issue(owner);
    var member = newUser(null);
    var key = UUID.randomUUID();
    var first =
        postJoin(member, invitation, key)
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.role").value("MEMBER"))
            .andReturn()
            .getResponse();
    assertThat(mapper.readTree(first.getContentAsString()).get("data").size()).isEqualTo(4);
    var joined = invitations.join(member, token(invitation), key);
    assertThat(joined.created()).isTrue();
    assertThat(joined.response().joinedAt()).isEqualTo(NOW);
    assertThat(
            jdbc.queryForObject(
                "SELECT invitation_id FROM space_membership WHERE id = ?",
                UUID.class,
                joined.response().membershipId()))
        .isEqualTo(invitation.invitationId());
    assertThat(
            jdbc.queryForObject(
                    "SELECT updated_at FROM care_space WHERE id = ?", Timestamp.class, space)
                .toInstant())
        .isEqualTo(NOW);
    postJoin(member, invitation, key).andExpect(status().isCreated());
    postJoin(member, invitation, UUID.randomUUID())
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.membershipId").value(joined.response().membershipId().toString()));
    postJoin(owner, invitation, UUID.randomUUID())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.role").value("OWNER"));
    assertThat(version()).isEqualTo(2);
    assertThat(count("space_membership", "space_id", space)).isEqualTo(2);
  }

  @Test
  void exactExpiryRejectsEvenCurrentMembersButSuccessfulReplayStillWorks() throws Exception {
    var invitation = issue(owner);
    var member = newUser("가족");
    var key = UUID.randomUUID();
    var success = invitations.join(member, token(invitation), key);
    when(clock.instant()).thenReturn(invitation.expiresAt().minusNanos(1000));
    assertThat(invitations.preview(owner, token(invitation)).isMember()).isTrue();
    when(clock.instant()).thenReturn(invitation.expiresAt());
    mvc.perform(
            get("/api/invitations/{token}", token(invitation)).with(authentication(login(owner))))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));
    postJoin(member, invitation, UUID.randomUUID()).andExpect(status().isGone());
    assertThat(invitations.join(member, token(invitation), key)).isEqualTo(success);
    postJoin(member, invitation, key).andExpect(status().isCreated());
    assertThat(version()).isEqualTo(2);
  }

  @Test
  void endedIssuerInvalidatesLinksPermanentlyButNotSuccessfulJoinReceipts() {
    var ownerLink = issue(owner);
    var issuer = newUser("발급 구성원");
    var issuerJoin = invitations.join(issuer, token(ownerLink), UUID.randomUUID());
    var issueKey = UUID.randomUUID();
    var link = invitations.issue(issuer, space, issueKey);
    var member = newUser("참여 가족");
    var joinKey = UUID.randomUUID();
    var joined = invitations.join(member, token(link), joinKey);
    end(issuerJoin.response().membershipId(), NOW.plusSeconds(1), "LEFT");
    when(clock.instant()).thenReturn(NOW.plusSeconds(2));
    assertCode(
        () -> invitations.preview(member, token(link)), CareSpaceErrorCode.INVITATION_UNAVAILABLE);
    assertCode(
        () -> invitations.join(member, token(link), UUID.randomUUID()),
        CareSpaceErrorCode.INVITATION_UNAVAILABLE);
    assertThat(invitations.join(member, token(link), joinKey)).isEqualTo(joined);
    var newLink = issue(owner);
    invitations.join(issuer, token(newLink), UUID.randomUUID());
    assertCode(
        () -> invitations.preview(member, token(link)), CareSpaceErrorCode.INVITATION_UNAVAILABLE);
    assertCode(() -> invitations.issue(issuer, space, issueKey), CommonErrorCode.FORBIDDEN);
  }

  @ParameterizedTest
  @ValueSource(strings = {"LEFT", "REMOVED"})
  void rejoinRequiresALinkIssuedStrictlyAfterLatestDepartureAndANewRequest(String reason) {
    var link = issue(owner);
    var member = newUser("재참여자");
    var key = UUID.randomUUID();
    var original = invitations.join(member, token(link), key);
    end(original.response().membershipId(), NOW.plusSeconds(5), reason);
    when(clock.instant()).thenReturn(NOW.plusSeconds(5));
    var equalTime = issue(owner);
    assertCode(
        () -> invitations.preview(member, token(link)), CareSpaceErrorCode.INVITATION_UNAVAILABLE);
    assertCode(
        () -> invitations.join(member, token(equalTime), UUID.randomUUID()),
        CareSpaceErrorCode.INVITATION_UNAVAILABLE);
    assertCode(() -> invitations.join(member, token(link), key), CommonErrorCode.FORBIDDEN);
    when(clock.instant()).thenReturn(NOW.plusSeconds(6));
    var fresh = issue(owner);
    var rejoined = invitations.join(member, token(fresh), UUID.randomUUID());
    assertThat(rejoined.response().membershipId()).isNotEqualTo(original.response().membershipId());
    assertCode(() -> invitations.join(member, token(link), key), CommonErrorCode.FORBIDDEN);
    assertThat(count("space_membership", "user_id", member)).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM space_membership WHERE user_id = ? AND is_active",
                Long.class,
                member))
        .isEqualTo(1);
    // A second departure must supersede the previous rejoin invitation as well.
    end(rejoined.response().membershipId(), NOW.plusSeconds(7), reason);
    when(clock.instant()).thenReturn(NOW.plusSeconds(8));
    assertCode(
        () -> invitations.join(member, token(fresh), UUID.randomUUID()),
        CareSpaceErrorCode.INVITATION_UNAVAILABLE);
  }

  @Test
  void requestNamespaceRejectsOtherOperationsSpacesAndTokensButIsPerActor() {
    var key = UUID.randomUUID();
    var link = invitations.issue(owner, space, key);
    var otherSpace =
        creation.create(owner, new CreateCareSpaceRequest("아빠", UUID.randomUUID())).spaceId();
    assertCode(
        () -> invitations.issue(owner, otherSpace, key), CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    assertCode(
        () -> invitations.join(owner, token(link), key), CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    assertCode(
        () -> creation.create(owner, new CreateCareSpaceRequest("할머니", key)),
        CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    var member = newUser("가족");
    invitations.join(member, token(link), key);
    var otherLink = issue(owner);
    assertCode(
        () -> invitations.join(member, token(otherLink), key),
        CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
  }

  @Test
  void securityValidationAndMembershipGuardAllEndpoints() throws Exception {
    var link = issue(owner);
    var outsider = newUser("외부인");
    mvc.perform(get("/api/invitations/{token}", token(link))).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/spaces/{id}/members", space)).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/spaces/{id}/invitations", space)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/invitations/{token}/join", token(link))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    for (String path :
        List.of(
            "/api/spaces/" + space + "/invitations", "/api/invitations/" + token(link) + "/join")) {
      mvc.perform(
              post(path)
                  .with(authentication(login(owner)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isForbidden());
      mvc.perform(
              post(path)
                  .with(authentication(login(owner)))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].field").value("clientRequestId"));
      mvc.perform(
              post(path)
                  .with(authentication(login(owner)))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"clientRequestId\":\"invalid\"}"))
          .andExpect(status().isBadRequest());
    }
    postIssue(outsider, space, UUID.randomUUID()).andExpect(status().isForbidden());
    postIssue(owner, UUID.randomUUID(), UUID.randomUUID()).andExpect(status().isNotFound());
    mvc.perform(get("/api/spaces/{id}/members", space).with(authentication(login(outsider))))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/spaces/{id}/members", UUID.randomUUID()).with(authentication(login(owner))))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/invitations/{token}", "bad-token").with(authentication(login(owner))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVITATION_UNAVAILABLE"));
    mvc.perform(get("/api/invitations/{token}", "x".repeat(43)).with(authentication(login(owner))))
        .andExpect(status().isNotFound());
    jdbc.update("DELETE FROM social_account WHERE user_id = ?", outsider);
    postJoin(outsider, link, UUID.randomUUID()).andExpect(status().isUnauthorized());
  }

  @Test
  void deletingSpaceRejectsReadsWritesAndSuccessReplays() throws Exception {
    var key = UUID.randomUUID();
    var link = invitations.issue(owner, space, key);
    var member = newUser("가족");
    var joinKey = UUID.randomUUID();
    invitations.join(member, token(link), joinKey);
    jdbc.update("UPDATE care_space SET lifecycle = 'DELETING' WHERE id = ?", space);
    postIssue(owner, space, key).andExpect(status().isNotFound());
    postJoin(member, link, joinKey)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVITATION_UNAVAILABLE"));
    assertCode(
        () -> invitations.preview(member, token(link)), CareSpaceErrorCode.INVITATION_UNAVAILABLE);
    assertCode(() -> members.list(owner, space), CommonErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void listsOnlyCurrentMembersOwnerFirstWithStableTiesAndNullableNames() throws Exception {
    var link = issue(owner);
    var a = newUser(null);
    var b = newUser("이름");
    var removed = newUser("나간 사람");
    invitations.join(a, token(link), UUID.randomUUID());
    invitations.join(b, token(link), UUID.randomUUID());
    var ended = invitations.join(removed, token(link), UUID.randomUUID());
    end(ended.response().membershipId(), NOW.plusSeconds(1), "LEFT");
    var list = members.list(a, space).items();
    assertThat(list).hasSize(3);
    assertThat(list.getFirst().role()).isEqualTo(CareSpaceRole.OWNER);
    assertThat(list.stream().filter(item -> item.isMe()).toList())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.displayName()).isNull();
              assertThat(item.role()).isEqualTo(CareSpaceRole.MEMBER);
            });
    var expectedIds =
        jdbc.queryForList(
            "SELECT id FROM space_membership WHERE space_id = ? AND is_active ORDER BY (user_id = ?) DESC, joined_at, id",
            UUID.class,
            space,
            owner);
    assertThat(list).extracting(item -> item.membershipId()).containsExactlyElementsOf(expectedIds);
    var response =
        mvc.perform(get("/api/spaces/{id}/members", space).with(authentication(login(a))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse();
    var data = mapper.readTree(response.getContentAsString()).get("data");
    assertThat(data.size()).isEqualTo(1);
    for (var item : data.get("items")) assertThat(item.size()).isEqualTo(4);
    assertThat(response.getContentAsString())
        .doesNotContain("assignedTaskCount", "userId", "나간 사람");
  }

  @Test
  void receiptFailureRollsBackIssuedLinkAndNewParticipationWithVersion() {
    var issueKey = UUID.randomUUID();
    doThrow(new DataAccessResourceFailureException("simulated failure"))
        .when(receiptSpy)
        .saveSuccess(any(), any(), any(), any());
    assertThatThrownBy(() -> invitations.issue(owner, space, issueKey))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(count("invitation", "space_id", space)).isZero();
    doCallRealMethod().when(receiptSpy).saveSuccess(any(), any(), any(), any());
    var link = invitations.issue(owner, space, issueKey);
    var member = newUser("가족");
    var key = UUID.randomUUID();
    doThrow(new DataAccessResourceFailureException("simulated failure"))
        .when(receiptSpy)
        .saveSuccess(any(), any(), any(), any());
    assertThatThrownBy(() -> invitations.join(member, token(link), key))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(count("space_membership", "user_id", member)).isZero();
    assertThat(count("command_receipt", "actor_user_id", member)).isZero();
    assertThat(version()).isEqualTo(1);
    doCallRealMethod().when(receiptSpy).saveSuccess(any(), any(), any(), any());
    assertThat(invitations.join(member, token(link), key).created()).isTrue();
  }

  @Test
  void concurrentJoinsForTheSameUserCreateExactlyOneMembership() throws Exception {
    var link = issue(owner);
    var member = newUser("가족");
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a =
          executor.submit(
              () -> {
                await(start);
                return invitations.join(member, token(link), UUID.randomUUID()).created();
              });
      var b =
          executor.submit(
              () -> {
                await(start);
                return invitations.join(member, token(link), UUID.randomUUID()).created();
              });
      start.countDown();
      assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(count("space_membership", "user_id", member)).isEqualTo(1);
    assertThat(version()).isEqualTo(2);
  }

  @Test
  void concurrentDifferentUsersCanUseTheSameLinkWithoutLostVersionUpdates() throws Exception {
    var link = issue(owner);
    var aUser = newUser("가족1");
    var bUser = newUser("가족2");
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a =
          executor.submit(
              () -> {
                await(start);
                return invitations.join(aUser, token(link), UUID.randomUUID()).created();
              });
      var b =
          executor.submit(
              () -> {
                await(start);
                return invitations.join(bUser, token(link), UUID.randomUUID()).created();
              });
      start.countDown();
      assertThat(a.get(15, TimeUnit.SECONDS)).isTrue();
      assertThat(b.get(15, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(version()).isEqualTo(3);
  }

  @Test
  void heldRequestLocksReturnConflictAndReleaseOnTransactionCompletion() throws Exception {
    var link = issue(owner);
    var member = newUser("가족");
    var key = UUID.randomUUID();
    var ready = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var held =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          tx -> {
                            assertThat(receipts.tryLock(member, key)).isTrue();
                            ready.countDown();
                            await(release);
                          }));
      try {
        await(ready);
        postJoin(member, link, key)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));
      } finally {
        release.countDown();
      }
      held.get(15, TimeUnit.SECONDS);
    }
    assertThat(invitations.join(member, token(link), key).created()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void revalidatesIssuerAndExpiryAfterWaitingForTheSpaceLock(boolean expires) throws Exception {
    var ownerLink = issue(owner);
    var issuer = newUser("발급자");
    var issuerMembership =
        invitations.join(issuer, token(ownerLink), UUID.randomUUID()).response().membershipId();
    var link = issue(issuer);
    var newcomer = newUser("참여자");
    var locked = new CountDownLatch(1);
    var readInvitation = new CountDownLatch(1);
    InvitationRepository repositorySpy = AopTestUtils.getUltimateTargetObject(invitationRepository);
    doAnswer(
            call -> {
              readInvitation.countDown();
              return call.callRealMethod();
            })
        .when(repositorySpy)
        .lockActiveSpace(space);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var change =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          tx -> {
                            jdbc.queryForObject(
                                "SELECT id FROM care_space WHERE id = ? FOR UPDATE",
                                UUID.class,
                                space);
                            locked.countDown();
                            await(readInvitation);
                            if (expires) when(clock.instant()).thenReturn(link.expiresAt());
                            else end(issuerMembership, NOW.plusSeconds(1), "LEFT");
                          }));
      await(locked);
      var joining =
          executor.submit(
              () -> {
                try {
                  invitations.join(newcomer, token(link), UUID.randomUUID());
                  return "SUCCESS";
                } catch (ApiException error) {
                  return error.code().code();
                }
              });
      change.get(15, TimeUnit.SECONDS);
      assertThat(joining.get(15, TimeUnit.SECONDS))
          .isEqualTo(expires ? "INVITATION_EXPIRED" : "INVITATION_UNAVAILABLE");
    }
    assertThat(count("space_membership", "user_id", newcomer)).isZero();
    assertThat(count("command_receipt", "actor_user_id", newcomer)).isZero();
    assertThat(version()).isEqualTo(2);
  }

  @Test
  void swaggerDocumentsActualInputOutputsAndSessionAndCsrfTogether() throws Exception {
    var doc =
        mapper.readTree(
            mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    for (var path :
        List.of(
            "~1api~1spaces~1{spaceId}~1invitations",
            "~1api~1invitations~1{invitationToken}~1join")) {
      var operation = doc.at("/paths/" + path + "/post");
      assertThat(operation.at("/security").size()).isEqualTo(1);
      assertThat(operation.at("/security/0").size()).isEqualTo(2);
      assertThat(operation.at("/security/0").has("SessionCookie")).isTrue();
      assertThat(operation.at("/security/0").has("CsrfToken")).isTrue();
      assertThat(operation.at("/parameters").size()).isEqualTo(1);
      assertThat(operation.at("/responses/409/$ref").asText())
          .endsWith("INVITATION_COMMAND_CONFLICT");
    }
    assertThat(
            doc.at(
                    "/components/responses/INVITATION_COMMAND_CONFLICT/content/application~1json/examples")
                .size())
        .isEqualTo(2);
    assertThat(doc.at("/components/schemas/InvitationRequest/properties").size()).isEqualTo(1);
    assertThat(doc.at("/components/schemas/InvitationPreview/properties").size()).isEqualTo(4);
    assertThat(doc.at("/components/schemas/SpaceMemberSummary/properties").size()).isEqualTo(4);
    for (var code : List.of("200", "201")) {
      assertThat(
              doc.at(
                      "/paths/~1api~1invitations~1{invitationToken}~1join/post/responses/"
                          + code
                          + "/content/application~1json/schema/$ref")
                  .asText())
          .contains("JoinInvitationResponse");
    }
  }

  private CreateInvitationResponse issue(UUID actor) {
    return invitations.issue(actor, space, UUID.randomUUID());
  }

  private String token(CreateInvitationResponse response) {
    return response.invitationUrl().substring(response.invitationUrl().lastIndexOf('/') + 1);
  }

  private long version() {
    return jdbc.queryForObject("SELECT version FROM care_space WHERE id = ?", Long.class, space);
  }

  private long count(String table, String field, UUID id) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + field + " = ?", Long.class, id);
  }

  private void end(UUID membership, Instant ended, String reason) {
    jdbc.update(
        "UPDATE space_membership SET ended_at = ?, end_reason = ? WHERE id = ?",
        Timestamp.from(ended),
        reason,
        membership);
  }

  private UUID newUser(String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO app_user (id, display_name, created_at, updated_at) VALUES (?, ?, now(), now())",
        id,
        name);
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

  private ResultActions postIssue(UUID actor, UUID spaceId, UUID key) throws Exception {
    return mvc.perform(
        post("/api/spaces/{id}/invitations", spaceId)
            .with(authentication(login(actor)))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("clientRequestId", key))));
  }

  private ResultActions postJoin(UUID actor, CreateInvitationResponse link, UUID key)
      throws Exception {
    return mvc.perform(
        post("/api/invitations/{token}/join", token(link))
            .with(authentication(login(actor)))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("clientRequestId", key))));
  }

  private static void assertCode(Runnable action, ErrorCode code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.code()).isEqualTo(code));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }
}
