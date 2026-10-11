package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.hanmaum.backend.carespace.dto.MembershipChangeRequest;
import com.hanmaum.backend.carespace.dto.TransferOwnershipRequest;
import com.hanmaum.backend.carespace.service.CareSpaceCreationService;
import com.hanmaum.backend.carespace.service.CareSpaceQueryService;
import com.hanmaum.backend.carespace.service.InvitationService;
import com.hanmaum.backend.carespace.service.MembershipLifecycleService;
import com.hanmaum.backend.carespace.service.SpaceMemberQueryService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.code.ErrorCode;
import com.hanmaum.backend.global.command.CommandReceiptRepository;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.security.oauth.MemberOAuth2User;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
class MembershipLifecycleIntegrationTests {
  private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired CareSpaceCreationService creation;
  @Autowired CareSpaceQueryService spaces;
  @Autowired InvitationService invitations;
  @Autowired SpaceMemberQueryService members;
  @Autowired MembershipLifecycleService lifecycle;
  @Autowired PlatformTransactionManager transactions;
  @MockitoBean Clock clock;
  @MockitoSpyBean CommandReceiptRepository receipts;
  CommandReceiptRepository receiptSpy;
  UUID owner;
  UUID ownerMembership;
  UUID member;
  UUID memberMembership;
  UUID space;
  CreateInvitationResponse link;

  @BeforeEach
  void setUp() {
    when(clock.instant()).thenReturn(NOW);
    receiptSpy = AopTestUtils.getUltimateTargetObject(receipts);
    owner = newUser();
    var created = creation.create(owner, new CreateCareSpaceRequest("엄마", UUID.randomUUID()));
    space = created.spaceId();
    ownerMembership = created.membershipId();
    member = newUser();
    link = invitations.issue(owner, space, UUID.randomUUID());
    memberMembership =
        invitations.join(member, token(link), UUID.randomUUID()).response().membershipId();
  }

  @Test
  void transferChangesBothRolesAtomicallyAndReplaysHistoricalResultWithoutOwnerAuthority()
      throws Exception {
    var request = transferRequest(memberMembership);
    when(clock.instant()).thenReturn(NOW.plusSeconds(10));
    var result =
        call(owner, "/ownership-transfers", request)
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.myRole").value("MEMBER"))
            .andExpect(jsonPath("$.data.spaceVersion").value("3"))
            .andReturn()
            .getResponse();
    assertThat(mapper.readTree(result.getContentAsString()).size()).isEqualTo(6);
    assertThat(mapper.readTree(result.getContentAsString()).get("data").size()).isEqualTo(4);
    assertThat(ownerId(space)).isEqualTo(memberMembership);
    assertThat(
            jdbc.queryForObject(
                "SELECT owner_user_id FROM care_space WHERE id = ?", UUID.class, space))
        .isEqualTo(member);
    assertThat(members.list(member, space).items().getFirst().membershipId())
        .isEqualTo(memberMembership);
    assertThat(
            members.list(owner, space).items().stream()
                .filter(x -> x.isMe())
                .findFirst()
                .orElseThrow()
                .role())
        .isEqualTo(CareSpaceRole.MEMBER);
    var original = lifecycle.transfer(owner, space, request);
    assertThat(version()).isEqualTo(3);
    assertThat(updatedAt()).isEqualTo(NOW.plusSeconds(10));
    assertCode(
        () -> lifecycle.transfer(owner, space, transferRequest(ownerMembership)),
        CommonErrorCode.FORBIDDEN);
    // A later transfer back must not cause the old command to transfer again or claim current role.
    lifecycle.transfer(member, space, transferRequest(ownerMembership));
    assertThat(lifecycle.transfer(owner, space, request)).isEqualTo(original);
    assertThat(ownerId(space)).isEqualTo(ownerMembership);
    assertThat(version()).isEqualTo(4);
    assertThat(invitations.preview(member, token(link)).isMember()).isTrue();
    creation.create(member, new CreateCareSpaceRequest("엄마", UUID.randomUUID()));
  }

  @Test
  void duplicateOwnerLabelRollsBackEverythingAndRequestCanBeRetriedAfterConflictResolved() {
    UUID other =
        creation.create(member, new CreateCareSpaceRequest("엄마", UUID.randomUUID())).spaceId();
    var request = transferRequest(memberMembership);
    assertCode(
        () -> lifecycle.transfer(owner, space, request), CareSpaceErrorCode.SPACE_LABEL_DUPLICATED);
    assertThat(ownerId(space)).isEqualTo(ownerMembership);
    assertThat(version()).isEqualTo(2);
    assertThat(receiptCount(owner, request.clientRequestId())).isZero();
    // Simulate a separately authorized label change; no label-change API belongs to this issue.
    jdbc.update("UPDATE care_space SET subject_label = '아빠' WHERE id = ?", other);
    assertThat(lifecycle.transfer(owner, space, request).ownerMembershipId())
        .isEqualTo(memberMembership);
    creation.create(owner, new CreateCareSpaceRequest("엄마", UUID.randomUUID()));
  }

  @Test
  void rejectsWrongSpaceMissingEndedSelfAndUnauthorizedTargets() {
    UUID outsider = newUser();
    UUID otherMembership =
        creation
            .create(outsider, new CreateCareSpaceRequest("다른 공간", UUID.randomUUID()))
            .membershipId();
    assertCode(
        () -> lifecycle.transfer(owner, space, transferRequest(otherMembership)),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    assertCode(
        () -> lifecycle.remove(owner, space, UUID.randomUUID(), change()),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    assertCode(
        () -> lifecycle.transfer(owner, space, transferRequest(ownerMembership)),
        CareSpaceErrorCode.MEMBERSHIP_STATE_CONFLICT);
    assertCode(
        () -> lifecycle.remove(owner, space, ownerMembership, change()),
        CareSpaceErrorCode.MEMBERSHIP_STATE_CONFLICT);
    assertCode(
        () -> lifecycle.remove(member, space, ownerMembership, change()),
        CommonErrorCode.FORBIDDEN);
    assertCode(
        () -> lifecycle.transfer(outsider, space, transferRequest(memberMembership)),
        CommonErrorCode.FORBIDDEN);
    assertCode(
        () -> lifecycle.depart(owner, space, change()), CareSpaceErrorCode.OWNER_TRANSFER_REQUIRED);
    lifecycle.depart(member, space, change());
    assertCode(
        () -> lifecycle.transfer(owner, space, transferRequest(memberMembership)),
        CareSpaceErrorCode.MEMBERSHIP_STATE_CONFLICT);
    assertCode(
        () -> lifecycle.remove(owner, space, memberMembership, change()),
        CareSpaceErrorCode.MEMBERSHIP_STATE_CONFLICT);
  }

  @Test
  void departureRevokesOnlyThisParticipationAndReturnsOnlyOwnMinimalReceipt() throws Exception {
    var ownLink = invitations.issue(member, space, UUID.randomUUID());
    UUID otherSpace =
        creation.create(member, new CreateCareSpaceRequest("다른 공간", UUID.randomUUID())).spaceId();
    var request = change();
    when(clock.instant()).thenReturn(NOW.plusSeconds(10));
    var result =
        call(member, "/departures", request)
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.membershipId").value(memberMembership.toString()))
            .andReturn()
            .getResponse();
    assertThat(mapper.readTree(result.getContentAsString()).get("data").size()).isEqualTo(2);
    assertThat(result.getContentAsString())
        .doesNotContain("unassignedTaskCount", "spaceVersion", "subjectLabel");
    assertEnded(memberMembership, "LEFT");
    assertThat(version()).isEqualTo(3);
    assertThat(updatedAt()).isEqualTo(NOW.plusSeconds(10));
    assertThat(members.list(owner, space).items()).hasSize(1);
    assertThat(members.list(member, otherSpace).items()).hasSize(1);
    assertThat(spaces.list(member).items())
        .extracting(item -> item.spaceId())
        .containsExactly(otherSpace);
    assertCode(() -> spaces.detail(member, space), CommonErrorCode.FORBIDDEN);
    mvc.perform(get("/api/users/me").with(authentication(login(member))))
        .andExpect(status().isOk());
    assertCode(() -> members.list(member, space), CommonErrorCode.FORBIDDEN);
    assertCode(
        () -> invitations.issue(member, space, UUID.randomUUID()), CommonErrorCode.FORBIDDEN);
    assertCode(
        () -> invitations.preview(newUser(), token(ownLink)),
        CareSpaceErrorCode.INVITATION_UNAVAILABLE);
    var receipt = lifecycle.depart(member, space, request);
    assertThat(receipt.endedAt()).isEqualTo(NOW.plusSeconds(10));
    assertCode(() -> lifecycle.depart(member, space, change()), CommonErrorCode.FORBIDDEN);
    // Successful self-departure proof is safe even when no current space access remains.
    jdbc.update("UPDATE care_space SET lifecycle = 'DELETING' WHERE id = ?", space);
    assertThat(lifecycle.depart(member, space, request)).isEqualTo(receipt);
    call(member, "/departures", request).andExpect(status().isOk());
    jdbc.update("DELETE FROM social_account WHERE user_id = ?", member);
    call(member, "/departures", request).andExpect(status().isUnauthorized());
  }

  @Test
  void oldDepartureReceiptNeverTerminatesRejoinedMembershipAndOldLinksStayInvalid() {
    var ownLink = invitations.issue(member, space, UUID.randomUUID());
    var request = change();
    var receipt = lifecycle.depart(member, space, request);
    assertCode(
        () -> invitations.join(member, token(link), UUID.randomUUID()),
        CareSpaceErrorCode.INVITATION_UNAVAILABLE);
    when(clock.instant()).thenReturn(NOW.plusSeconds(1));
    var fresh = invitations.issue(owner, space, UUID.randomUUID());
    UUID rejoined =
        invitations.join(member, token(fresh), UUID.randomUUID()).response().membershipId();
    assertThat(rejoined).isNotEqualTo(memberMembership);
    long before = version();
    assertThat(lifecycle.depart(member, space, request)).isEqualTo(receipt);
    assertThat(active(rejoined)).isTrue();
    assertThat(version()).isEqualTo(before);
    assertCode(
        () -> invitations.preview(newUser(), token(ownLink)),
        CareSpaceErrorCode.INVITATION_UNAVAILABLE);
  }

  @Test
  void removalReplaysOnlyForOriginalCurrentOwnerAndDoesNotRemoveRejoinedTarget() throws Exception {
    var request = change();
    String path = "/memberships/" + memberMembership + "/removals";
    var response =
        call(owner, path, request)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.spaceVersion").value("3"))
            .andReturn()
            .getResponse();
    assertThat(mapper.readTree(response.getContentAsString()).get("data").size()).isEqualTo(3);
    assertEnded(memberMembership, "REMOVED");
    var original = lifecycle.remove(owner, space, memberMembership, request);
    when(clock.instant()).thenReturn(NOW.plusSeconds(1));
    var fresh = invitations.issue(owner, space, UUID.randomUUID());
    UUID rejoined =
        invitations.join(member, token(fresh), UUID.randomUUID()).response().membershipId();
    assertThat(lifecycle.remove(owner, space, memberMembership, request)).isEqualTo(original);
    assertThat(active(rejoined)).isTrue();
    lifecycle.transfer(owner, space, transferRequest(rejoined));
    assertCode(
        () -> lifecycle.remove(owner, space, memberMembership, request), CommonErrorCode.FORBIDDEN);
  }

  @Test
  void transferReceiptCannotBeRecoveredUsingANewParticipation() {
    var request = transferRequest(memberMembership);
    lifecycle.transfer(owner, space, request);
    lifecycle.depart(owner, space, change());
    assertCode(() -> lifecycle.transfer(owner, space, request), CommonErrorCode.FORBIDDEN);
    when(clock.instant()).thenReturn(NOW.plusSeconds(1));
    var fresh = invitations.issue(member, space, UUID.randomUUID());
    invitations.join(owner, token(fresh), UUID.randomUUID());
    assertCode(() -> lifecycle.transfer(owner, space, request), CommonErrorCode.FORBIDDEN);
  }

  @Test
  void staleVersionRejectsEveryMutationWithoutReceiptOrChanges() throws Exception {
    var request = new MembershipChangeRequest("1", UUID.randomUUID());
    var transfer = new TransferOwnershipRequest(memberMembership, "1", UUID.randomUUID());
    for (var outcome :
        List.of(
            call(owner, "/ownership-transfers", transfer),
            call(owner, "/memberships/" + memberMembership + "/removals", request),
            call(member, "/departures", request))) {
      outcome
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }
    assertThat(version()).isEqualTo(2);
    assertThat(active(memberMembership)).isTrue();
    assertThat(receiptCount(owner, request.clientRequestId())).isZero();
    assertThat(receiptCount(member, request.clientRequestId())).isZero();
  }

  @Test
  void requestKeysBindActorKindSpaceTargetAndVersion() {
    var request = transferRequest(memberMembership);
    lifecycle.transfer(owner, space, request);
    assertCode(
        () ->
            lifecycle.transfer(
                owner,
                space,
                new TransferOwnershipRequest(ownerMembership, "2", request.clientRequestId())),
        CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    assertCode(
        () ->
            lifecycle.transfer(
                owner,
                space,
                new TransferOwnershipRequest(memberMembership, "3", request.clientRequestId())),
        CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    assertCode(
        () -> lifecycle.transfer(owner, UUID.randomUUID(), request),
        CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    assertCode(
        () ->
            lifecycle.depart(
                owner, space, new MembershipChangeRequest("2", request.clientRequestId())),
        CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    assertCode(
        () -> invitations.issue(owner, space, request.clientRequestId()),
        CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    // The same UUID belongs to a different actor's independent request namespace.
    lifecycle.transfer(
        member,
        space,
        new TransferOwnershipRequest(ownerMembership, "3", request.clientRequestId()));
    assertThat(version()).isEqualTo(4);
  }

  @ParameterizedTest
  @ValueSource(strings = {"transfer", "depart", "remove"})
  void receiptFailureRollsBackTheEntireMutation(String operation) {
    var before = updatedAt();
    doThrow(new DataAccessResourceFailureException("simulated failure"))
        .when(receiptSpy)
        .saveSuccess(any(), any(), any(), any());
    var key = UUID.randomUUID();
    Supplier<?> action =
        switch (operation) {
          case "transfer" ->
              () ->
                  lifecycle.transfer(
                      owner, space, new TransferOwnershipRequest(memberMembership, "2", key));
          case "depart" ->
              () -> lifecycle.depart(member, space, new MembershipChangeRequest("2", key));
          default ->
              () ->
                  lifecycle.remove(
                      owner, space, memberMembership, new MembershipChangeRequest("2", key));
        };
    assertThatThrownBy(action::get).isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(version()).isEqualTo(2);
    assertThat(updatedAt()).isEqualTo(before);
    assertThat(ownerId(space)).isEqualTo(ownerMembership);
    assertThat(active(memberMembership)).isTrue();
    assertThat(receiptCount(owner, key) + receiptCount(member, key)).isZero();
    doCallRealMethod().when(receiptSpy).saveSuccess(any(), any(), any(), any());
    action.get();
    assertThat(version()).isEqualTo(3);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "01", "+2", "2.0", " 2", "", "9223372036854775808", "1e1"})
  void invalidDecimalVersionIsAFieldError(String invalid) throws Exception {
    call(member, "/departures", new MembershipChangeRequest(invalid, UUID.randomUUID()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.errors[0].field").value("expectedSpaceVersion"));
    assertThat(version()).isEqualTo(2);
  }

  @Test
  void allRoutesRequireSessionCsrfAndValidBodiesWithStringVersions() throws Exception {
    for (String suffix :
        List.of(
            "/departures",
            "/ownership-transfers",
            "/memberships/" + memberMembership + "/removals")) {
      String path = "/api/spaces/" + space + suffix;
      mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isUnauthorized());
      mvc.perform(
              post(path)
                  .with(authentication(login(owner)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isForbidden());
      call(owner, suffix, Map.of()).andExpect(status().isBadRequest());
      call(
              owner,
              suffix,
              Map.of(
                  "expectedSpaceVersion",
                  2,
                  "clientRequestId",
                  UUID.randomUUID(),
                  "targetMembershipId",
                  memberMembership))
          .andExpect(status().isBadRequest());
      call(
              owner,
              suffix,
              Map.of(
                  "expectedSpaceVersion",
                  "2",
                  "clientRequestId",
                  "bad",
                  "targetMembershipId",
                  memberMembership))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void deletingAndMissingSpaceBlockFreshChangesAndPrivilegedReceipts() {
    var request = transferRequest(memberMembership);
    lifecycle.transfer(owner, space, request);
    jdbc.update("UPDATE care_space SET lifecycle = 'DELETING' WHERE id = ?", space);
    assertCode(() -> lifecycle.transfer(owner, space, request), CommonErrorCode.RESOURCE_NOT_FOUND);
    assertCode(() -> lifecycle.depart(owner, space, change()), CommonErrorCode.RESOURCE_NOT_FOUND);
    assertCode(
        () -> lifecycle.remove(member, space, ownerMembership, change()),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    assertCode(
        () -> lifecycle.transfer(owner, UUID.randomUUID(), transferRequest(memberMembership)),
        CommonErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void heldRequestLockReturnsInProgressWithoutSideEffects() throws Exception {
    var request = change();
    var ready = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var held =
          executor.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .executeWithoutResult(
                          tx -> {
                            assertThat(receipts.tryLock(member, request.clientRequestId()))
                                .isTrue();
                            ready.countDown();
                            await(release);
                          }));
      try {
        await(ready);
        call(member, "/departures", request)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));
        assertThat(active(memberMembership)).isTrue();
      } finally {
        release.countDown();
      }
      held.get(15, TimeUnit.SECONDS);
    }
    lifecycle.depart(member, space, request);
    assertThat(version()).isEqualTo(3);
  }

  @Test
  void concurrentTransferAndDepartureNeverLeaveAnEndedOwner() throws Exception {
    var transfer = transferRequest(memberMembership);
    var depart = change();
    var outcomes =
        concurrently(
            () -> lifecycle.transfer(owner, space, transfer),
            () -> lifecycle.depart(member, space, depart));
    assertThat(outcomes).contains("SUCCESS");
    assertThat(outcomes.stream().filter("SUCCESS"::equals).count()).isEqualTo(1);
    assertThat(outcomes)
        .allMatch(
            x -> List.of("SUCCESS", "VERSION_CONFLICT", "OWNER_TRANSFER_REQUIRED").contains(x));
    assertThat(version()).isEqualTo(3);
    assertThat(active(ownerId(space))).isTrue();
  }

  @Test
  void concurrentRemovalAndDepartureHaveOnlyOneWinner() throws Exception {
    var request = change();
    var outcomes =
        concurrently(
            () -> lifecycle.remove(owner, space, memberMembership, request),
            () -> lifecycle.depart(member, space, request));
    assertThat(outcomes.stream().filter("SUCCESS"::equals).count()).isEqualTo(1);
    assertThat(outcomes)
        .allMatch(x -> List.of("SUCCESS", "VERSION_CONFLICT", "FORBIDDEN").contains(x));
    assertThat(version()).isEqualTo(3);
    assertThat(active(memberMembership)).isFalse();
  }

  @Test
  void concurrentSameDepartureKeyHasOneEffectAndCanAlwaysRecoverSuccess() throws Exception {
    var request = change();
    var outcomes =
        concurrently(
            () -> lifecycle.depart(member, space, request),
            () -> lifecycle.depart(member, space, request));
    assertThat(outcomes).contains("SUCCESS");
    assertThat(outcomes).allMatch(x -> List.of("SUCCESS", "REQUEST_IN_PROGRESS").contains(x));
    assertThat(lifecycle.depart(member, space, request).membershipId()).isEqualTo(memberMembership);
    assertThat(version()).isEqualTo(3);
    assertThat(receiptCount(member, request.clientRequestId())).isEqualTo(1);
  }

  @Test
  void crossSpaceConcurrentTransfersRespectCurrentOwnerLabelUniqueness() throws Exception {
    UUID secondOwner = newUser();
    UUID secondSpace =
        creation.create(secondOwner, new CreateCareSpaceRequest("엄마", UUID.randomUUID())).spaceId();
    var secondLink = invitations.issue(secondOwner, secondSpace, UUID.randomUUID());
    UUID secondMember =
        invitations.join(member, token(secondLink), UUID.randomUUID()).response().membershipId();
    var outcomes =
        concurrently(
            () -> lifecycle.transfer(owner, space, transferRequest(memberMembership)),
            () ->
                lifecycle.transfer(
                    secondOwner,
                    secondSpace,
                    new TransferOwnershipRequest(secondMember, "2", UUID.randomUUID())));
    assertThat(outcomes).containsExactlyInAnyOrder("SUCCESS", "SPACE_LABEL_DUPLICATED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM care_space WHERE owner_user_id = ? AND subject_label = '엄마'",
                Long.class,
                member))
        .isEqualTo(1);
  }

  @Test
  void swaggerDocumentsThreeRealEndpointsConflictsAndMinimalResponses() throws Exception {
    var doc =
        mapper.readTree(
            mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var cases =
        Map.of(
            "/ownership-transfers",
            "OWNERSHIP_TRANSFER_CONFLICT",
            "/departures",
            "SPACE_DEPARTURE_CONFLICT",
            "/memberships/{membershipId}/removals",
            "MEMBERSHIP_REMOVAL_CONFLICT");
    for (var entry : cases.entrySet()) {
      var operation = doc.get("paths").get("/api/spaces/{spaceId}" + entry.getKey()).get("post");
      var security = operation.get("security");
      assertThat(security.size()).isEqualTo(1);
      assertThat(security.get(0).has("SessionCookie")).isTrue();
      assertThat(security.get(0).has("CsrfToken")).isTrue();
      for (String code : List.of("200", "400", "401", "403", "404", "409", "500"))
        assertThat(operation.get("responses").has(code)).isTrue();
      assertThat(operation.get("responses").get("409").get("$ref").asText())
          .endsWith(entry.getValue());
      var examples =
          doc.get("components")
              .get("responses")
              .get(entry.getValue())
              .get("content")
              .get("application/json")
              .get("examples");
      assertThat(examples.has("REQUEST_IN_PROGRESS")).isTrue();
      assertThat(examples.has("VERSION_CONFLICT")).isTrue();
    }
    var schemas = doc.get("components").get("schemas");
    assertThat(schemas.get("DepartureResponse").get("properties").size()).isEqualTo(2);
    assertThat(schemas.get("RemoveMembershipResponse").get("properties").has("unassignedTaskCount"))
        .isFalse();
    assertThat(
            schemas
                .get("MembershipChangeRequest")
                .get("properties")
                .get("expectedSpaceVersion")
                .get("type")
                .asText())
        .isEqualTo("string");
  }

  @Test
  void concurrentTransferAndSpaceCreationCannotGiveTargetTwoOwnedSpacesWithSameLabel()
      throws Exception {
    var transfer = transferRequest(memberMembership);
    var outcomes =
        concurrently(
            () -> lifecycle.transfer(owner, space, transfer),
            () -> creation.create(member, new CreateCareSpaceRequest("엄마", UUID.randomUUID())));
    assertThat(outcomes).containsExactlyInAnyOrder("SUCCESS", "SPACE_LABEL_DUPLICATED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM care_space WHERE owner_user_id = ? AND subject_label = '엄마'",
                Long.class,
                member))
        .isEqualTo(1);
    assertThat(active(ownerId(space))).isTrue();
  }

  @Test
  void aDifferentUsersMatchingDepartureKeyCannotReadAnExistingReceipt() {
    UUID key = UUID.randomUUID();
    var request = new MembershipChangeRequest("2", key);
    lifecycle.depart(member, space, request);
    UUID outsider = newUser();
    assertCode(() -> lifecycle.depart(outsider, space, request), CommonErrorCode.FORBIDDEN);
    assertThat(receiptCount(outsider, key)).isZero();
    assertThat(version()).isEqualTo(3);
  }

  private List<String> concurrently(Supplier<?> a, Supplier<?> b) throws Exception {
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                await(start);
                return outcome(a);
              });
      var second =
          executor.submit(
              () -> {
                await(start);
                return outcome(b);
              });
      start.countDown();
      return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
    }
  }

  private static String outcome(Supplier<?> action) {
    try {
      action.get();
      return "SUCCESS";
    } catch (ApiException error) {
      return error.code().code();
    }
  }

  private TransferOwnershipRequest transferRequest(UUID target) {
    return new TransferOwnershipRequest(target, Long.toString(version()), UUID.randomUUID());
  }

  private MembershipChangeRequest change() {
    return new MembershipChangeRequest(Long.toString(version()), UUID.randomUUID());
  }

  private long version() {
    return jdbc.queryForObject("SELECT version FROM care_space WHERE id = ?", Long.class, space);
  }

  private Instant updatedAt() {
    return jdbc.queryForObject(
            "SELECT updated_at FROM care_space WHERE id = ?", Timestamp.class, space)
        .toInstant();
  }

  private UUID ownerId(UUID spaceId) {
    return jdbc.queryForObject(
        "SELECT owner_membership_id FROM care_space WHERE id = ?", UUID.class, spaceId);
  }

  private boolean active(UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT is_active FROM space_membership WHERE id = ?", Boolean.class, id));
  }

  private long receiptCount(UUID actor, UUID key) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM command_receipt WHERE actor_user_id = ? AND client_request_id = ?",
        Long.class,
        actor,
        key);
  }

  private String token(CreateInvitationResponse response) {
    return response.invitationUrl().substring(response.invitationUrl().lastIndexOf('/') + 1);
  }

  private void assertEnded(UUID id, String reason) {
    assertThat(active(id)).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT end_reason FROM space_membership WHERE id = ?", String.class, id))
        .isEqualTo(reason);
    assertThat(
            jdbc.queryForObject(
                "SELECT user_id FROM space_membership WHERE id = ?", UUID.class, id))
        .isNotNull();
  }

  private UUID newUser() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO app_user (id, display_name, created_at, updated_at) VALUES (?, '가족', now(), now())",
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
    return new OAuth2AuthenticationToken(
        new MemberOAuth2User(
            userId, new DefaultOAuth2User(authorities, Map.of("id", userId.toString()), "id")),
        authorities,
        "kakao");
  }

  private ResultActions call(UUID actor, String suffix, Object body) throws Exception {
    return mvc.perform(
        post("/api/spaces/" + space + suffix)
            .with(authentication(login(actor)))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(body)));
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
