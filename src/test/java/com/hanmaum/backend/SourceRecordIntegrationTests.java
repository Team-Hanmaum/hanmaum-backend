package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hanmaum.backend.carespace.dto.CreateCareSpaceRequest;
import com.hanmaum.backend.carespace.dto.CreateCareSpaceResponse;
import com.hanmaum.backend.carespace.service.CareSpaceAccessService;
import com.hanmaum.backend.carespace.service.CareSpaceCreationService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.command.CommandReceiptRepository;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.security.oauth.MemberOAuth2User;
import com.hanmaum.backend.record.dto.CreateSourceRecordRequest;
import com.hanmaum.backend.record.dto.CreateSourceRecordResponse;
import com.hanmaum.backend.record.repository.SourceRecordRepository;
import com.hanmaum.backend.record.service.SourceRecordCreationService;
import com.hanmaum.backend.record.service.SourceRecordQueryService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
class SourceRecordIntegrationTests {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired CareSpaceCreationService spaces;
  @Autowired CareSpaceAccessService access;
  @Autowired SourceRecordCreationService creation;
  @Autowired SourceRecordQueryService queries;
  @Autowired CommandReceiptRepository receipts;
  @Autowired PlatformTransactionManager transactionManager;
  @MockitoSpyBean SourceRecordRepository records;
  UUID owner;
  UUID member;
  UUID memberMembership;
  CreateCareSpaceResponse space;

  @BeforeEach
  void setUp() {
    owner = newUser("소유자");
    member = newUser("가족");
    space = spaces.create(owner, new CreateCareSpaceRequest("엄마", UUID.randomUUID()));
    memberMembership = join(member);
  }

  @Test
  void storesExactOriginalWithServerAuthorAndTimestampWithoutChangingSpace() throws Exception {
    String original = "　  오늘 병원에 다녀왔어요.\r\n다음 주 재진 😀\n  ";
    var before =
        jdbc.queryForMap(
            "SELECT version, updated_at FROM care_space WHERE id = ?", space.spaceId());
    UUID key = UUID.randomUUID();
    Instant earliest = Instant.now().minusSeconds(1);
    var response =
        postRecord(member, original, key)
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.version").value("1"))
            .andReturn()
            .getResponse();
    var body = mapper.readTree(response.getContentAsString());
    assertThat(body.size()).isEqualTo(6);
    assertThat(body.at("/data").size()).isEqualTo(4);
    assertThat(body.at("/data/analysis/status").asText()).isEqualTo("QUEUED");
    UUID id = UUID.fromString(body.at("/data/recordId").asText());
    assertThat(response.getHeader("Location")).isEqualTo(path() + "/" + id);
    var detail = queries.detail(member, space.spaceId(), id);
    assertThat(detail.content()).isEqualTo(original);
    assertThat(detail.author().membershipId()).isEqualTo(memberMembership);
    assertThat(detail.author().displayName()).isEqualTo("가족");
    assertThat(detail.recordedAt()).isBetween(earliest, Instant.now().plusSeconds(1));
    assertThat(detail.recordedAt().getNano() % 1000).isZero();
    assertThat(detail.recordedAt()).isEqualTo(Instant.parse(body.at("/data/recordedAt").asText()));
    assertThat(detail.isMine()).isTrue();
    assertThat(detail.canDelete()).isTrue();
    assertThat(detail.latestAnalysis().analysisId().toString())
        .isEqualTo(body.at("/data/analysis/analysisId").asText());
    assertThat(detail.proposalIds()).isEmpty();
    assertThat(
            jdbc.queryForMap(
                "SELECT version, updated_at FROM care_space WHERE id = ?", space.spaceId()))
        .isEqualTo(before);
    var receipt =
        jdbc.queryForMap(
            "SELECT request_digest, result_refs::text, id FROM command_receipt WHERE actor_user_id = ? AND client_request_id = ?",
            member,
            key);
    assertThat(receipt.get("request_digest").toString()).matches("[0-9a-f]{64}");
    assertThat(receipt.get("result_refs").toString())
        .doesNotContain("병원", "가족")
        .contains(id.toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT create_command_id FROM source_record WHERE id = ?", UUID.class, id))
        .isEqualTo(receipt.get("id"));
  }

  @Test
  void publicReadsUseCurrentRightsAndDoNotExposeAccountIds() throws Exception {
    var created = create(member, "소식");
    mvc.perform(get(path() + "/" + created.recordId()).with(authentication(login(owner))))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.data.isMine").value(false))
        .andExpect(jsonPath("$.data.canDelete").value(true))
        .andExpect(jsonPath("$.data.author.userId").doesNotExist())
        .andExpect(jsonPath("$.data.author.withdrawn").value(false));
    mvc.perform(get(path()).with(authentication(login(member))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].isMine").value(true))
        .andExpect(jsonPath("$.data.items[0].content").value("소식"))
        .andExpect(jsonPath("$.data.hasNext").value(false));
    UUID other = newUser(null);
    join(other);
    assertThat(queries.detail(other, space.spaceId(), created.recordId()).canDelete()).isFalse();
    var anonymousName = create(other, "이름 미설정 소식");
    assertThat(
            queries.detail(owner, space.spaceId(), anonymousName.recordId()).author().displayName())
        .isNull();
    assertThat(
            queries.detail(owner, space.spaceId(), anonymousName.recordId()).author().withdrawn())
        .isFalse();
  }

  @ParameterizedTest
  @MethodSource("invalidContent")
  void rejectsInvalidTextWithoutCreatingRecordsOrReceipts(String content) throws Exception {
    UUID key = UUID.randomUUID();
    postRecord(member, content, key)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.errors[0].field").value("content"));
    assertThat(recordCount()).isZero();
    assertThat(receiptCount(member, key)).isZero();
  }

  static Stream<String> invalidContent() {
    return Stream.of(
        null, "", " \t\r\n\u00a0\u3000", "가".repeat(10001), "😀".repeat(5001), "bad\0text");
  }

  @Test
  void rejectsEscapedUnpairedSurrogatesWithoutTransportReplacingThem() throws Exception {
    // A raw Java surrogate is replaced by '?' during UTF-8 request encoding. Send JSON escape text.
    mvc.perform(
            post(path())
                .with(authentication(login(member)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"content\":\"bad\\uD800\",\"clientRequestId\":\""
                        + UUID.randomUUID()
                        + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("content"));
    assertThat(recordCount()).isZero();
  }

  @Test
  void acceptsUtf16BoundaryAndDoesNotNormalizeLineEndingsOrUnicode() throws Exception {
    for (String text : List.of("가".repeat(10000), "😀".repeat(5000), "e\u0301\r\n가")) {
      postRecord(member, text, UUID.randomUUID()).andExpect(status().isCreated());
    }
    assertThat(recordCount()).isEqualTo(3);
  }

  @ParameterizedTest
  @ValueSource(strings = {"123", "true", "[]", "{}"})
  void rejectsNonStringContent(String value) throws Exception {
    mvc.perform(
            post(path())
                .with(authentication(login(member)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"content\":"
                        + value
                        + ",\"clientRequestId\":\""
                        + UUID.randomUUID()
                        + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void rejectsMissingRequestIdAndMalformedIdentifiers() throws Exception {
    postRecord(member, "소식", null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("clientRequestId"));
    mvc.perform(get(path() + "/bad-id").with(authentication(login(member))))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/spaces/bad-id/source-records").with(authentication(login(member))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void sessionAndCsrfAreRequiredOnlyWhereApplicable() throws Exception {
    mvc.perform(get(path())).andExpect(status().isUnauthorized());
    mvc.perform(
            post(path())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        new CreateSourceRecordRequest("소식", UUID.randomUUID()))))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post(path())
                .with(authentication(login(member)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        new CreateSourceRecordRequest("소식", UUID.randomUUID()))))
        .andExpect(status().isForbidden());
    mvc.perform(get(path()).with(authentication(login(member))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items").isEmpty());
    assertThat(recordCount()).isZero();
  }

  @Test
  void rejectsOutsidersAndWrongSpaceRecordsWithoutLeakingContent() throws Exception {
    var record = create(member, "민감한 원문");
    UUID outsider = newUser("외부인");
    mvc.perform(get(path()).with(authentication(login(outsider))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.data").isEmpty());
    mvc.perform(get(path() + "/" + record.recordId()).with(authentication(login(outsider))))
        .andExpect(status().isForbidden());
    postRecord(outsider, "무단 작성", UUID.randomUUID()).andExpect(status().isForbidden());
    var otherSpace = spaces.create(owner, new CreateCareSpaceRequest("아빠", UUID.randomUUID()));
    assertCode(
        () -> queries.detail(owner, otherSpace.spaceId(), record.recordId()),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    assertCode(
        () -> queries.detail(owner, space.spaceId(), UUID.randomUUID()),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    assertCode(
        () -> queries.list(owner, UUID.randomUUID(), null, 20), CommonErrorCode.RESOURCE_NOT_FOUND);
    assertCode(
        () -> creation.create(owner, UUID.randomUUID(), request("소식")),
        CommonErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void endedAuthorsRecordsRemainAndRejoinDoesNotReplayOldCommand() {
    var request = request("남겨진 소식");
    var record = creation.create(member, space.spaceId(), request);
    end(memberMembership);
    assertCode(() -> queries.list(member, space.spaceId(), null, 20), CommonErrorCode.FORBIDDEN);
    assertCode(
        () -> queries.detail(member, space.spaceId(), record.recordId()),
        CommonErrorCode.FORBIDDEN);
    assertCode(() -> creation.create(member, space.spaceId(), request), CommonErrorCode.FORBIDDEN);
    assertCode(() -> create(member, "종료 후 새 작성"), CommonErrorCode.FORBIDDEN);
    var preserved = queries.detail(owner, space.spaceId(), record.recordId());
    assertThat(preserved.content()).isEqualTo("남겨진 소식");
    assertThat(preserved.author().withdrawn()).isFalse();
    UUID rejoined = join(member);
    assertThat(rejoined).isNotEqualTo(memberMembership);
    var own = queries.detail(member, space.spaceId(), record.recordId());
    assertThat(own.isMine()).isTrue();
    assertThat(own.canDelete()).isTrue();
    assertThat(own.author().membershipId()).isEqualTo(memberMembership);
    assertCode(() -> creation.create(member, space.spaceId(), request), CommonErrorCode.FORBIDDEN);
    create(member, "재참여 후 새 작성");
  }

  @Test
  void disconnectedAuthorIsDifferentFromEndedParticipation() {
    var record = create(member, "공동 기록");
    end(memberMembership);
    jdbc.update("UPDATE space_membership SET user_id = NULL WHERE id = ?", memberMembership);
    var author = queries.detail(owner, space.spaceId(), record.recordId()).author();
    assertThat(author.withdrawn()).isTrue();
    assertThat(author.membershipId()).isNull();
    assertThat(author.displayName()).isNull();
  }

  @Test
  void sameRequestReturnsOriginalButDifferentContentSpaceOrActionConflicts() {
    var request = request("원문\r\n");
    var first = creation.create(member, space.spaceId(), request);
    assertThat(creation.create(member, space.spaceId(), request)).isEqualTo(first);
    assertThat(recordCount()).isEqualTo(1);
    assertThat(receiptCount(member, request.clientRequestId())).isEqualTo(1);
    assertCode(
        () ->
            creation.create(
                member,
                space.spaceId(),
                new CreateSourceRecordRequest("원문\n", request.clientRequestId())),
        CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    var other = spaces.create(member, new CreateCareSpaceRequest("다른 공간", UUID.randomUUID()));
    assertCode(
        () -> creation.create(member, other.spaceId(), request),
        CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    assertCode(
        () -> spaces.create(member, new CreateCareSpaceRequest("공간 생성", request.clientRequestId())),
        CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    UUID spaceRequest = UUID.randomUUID();
    spaces.create(member, new CreateCareSpaceRequest("별도 공간", spaceRequest));
    assertCode(
        () ->
            creation.create(
                member, space.spaceId(), new CreateSourceRecordRequest("원문", spaceRequest)),
        CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    create(member, request.content());
    assertThat(recordCount()).isEqualTo(2);
  }

  @Test
  void erasedRecordCannotBeReadListedOrRestoredByReplay() {
    var request = request("제거할 원문");
    var first = creation.create(member, space.spaceId(), request);
    erase(first.recordId());
    assertThat(queries.list(owner, space.spaceId(), null, 20).items()).isEmpty();
    assertCode(
        () -> queries.detail(owner, space.spaceId(), first.recordId()),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    assertCode(
        () -> creation.create(member, space.spaceId(), request),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    assertThat(
            jdbc.queryForObject(
                "SELECT body FROM source_record WHERE id = ?", String.class, first.recordId()))
        .isNull();
    assertThat(recordCount()).isEqualTo(1);
  }

  @Test
  void deletingSpaceHidesRecordsAndBlocksAllWritesIncludingReplay() {
    var request = request("기록");
    var record = creation.create(member, space.spaceId(), request);
    jdbc.update("UPDATE care_space SET lifecycle = 'DELETING' WHERE id = ?", space.spaceId());
    assertCode(
        () -> queries.list(member, space.spaceId(), null, 20), CommonErrorCode.RESOURCE_NOT_FOUND);
    assertCode(
        () -> queries.detail(owner, space.spaceId(), record.recordId()),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    assertCode(
        () -> creation.create(member, space.spaceId(), request),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    assertCode(() -> create(owner, "새 소식"), CommonErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void persistenceFailureRollsBackReceiptAndOriginalTogether() {
    SourceRecordRepository target = AopTestUtils.getUltimateTargetObject(records);
    doThrow(new DataAccessResourceFailureException("simulated failure"))
        .when(target)
        .insert(any(), any(), any());
    var request = request("저장 실패");
    assertThatThrownBy(() -> creation.create(member, space.spaceId(), request))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(recordCount()).isZero();
    assertThat(receiptCount(member, request.clientRequestId())).isZero();
  }

  @Test
  void paginationUsesTotalOrderAndSkipsNewRecordsWithoutDuplicatingEarlierPage() {
    Instant sameTime = Instant.parse("2026-01-01T00:00:00.123456Z");
    for (int i = 0; i < 7; i++) {
      var record = create(member, "소식 " + i);
      jdbc.update(
          "UPDATE source_record SET recorded_at = ? WHERE id = ?",
          Timestamp.from(sameTime),
          record.recordId());
    }
    var expected =
        jdbc.queryForList(
            "SELECT id FROM source_record WHERE space_id = ? ORDER BY recorded_at DESC, id DESC",
            UUID.class,
            space.spaceId());
    var first = queries.list(owner, space.spaceId(), null, 3);
    assertThat(first.items()).hasSize(3);
    assertThat(first.hasNext()).isTrue();
    assertThat(first.items().stream().map(r -> r.recordId()).toList())
        .isEqualTo(expected.subList(0, 3));
    create(member, "새로 추가된 소식");
    // Continuation does not depend on the cursor anchor still existing/remaining ACTIVE.
    erase(first.items().getLast().recordId());
    var second = queries.list(owner, space.spaceId(), first.nextCursor(), 3);
    var third = queries.list(owner, space.spaceId(), second.nextCursor(), 3);
    List<UUID> actual = new ArrayList<>();
    Stream.of(first, second, third)
        .flatMap(p -> p.items().stream())
        .forEach(r -> actual.add(r.recordId()));
    assertThat(actual).containsExactlyElementsOf(expected);
    assertThat(third.hasNext()).isFalse();
    assertThat(third.nextCursor()).isNull();
    assertThat(queries.list(owner, space.spaceId(), null, 3).items().getFirst().content())
        .isEqualTo("새로 추가된 소식");
  }

  @Test
  void emptyAndExactPageHaveNoCursorAndDefaultSizeIsTwenty() throws Exception {
    assertThat(queries.list(owner, space.spaceId(), null, 20).nextCursor()).isNull();
    for (int i = 0; i < 21; i++) create(owner, "소식 " + i);
    mvc.perform(get(path()).with(authentication(login(owner))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(20))
        .andExpect(jsonPath("$.data.hasNext").value(true));
    var exact = queries.list(owner, space.spaceId(), null, 21);
    assertThat(exact.items()).hasSize(21);
    assertThat(exact.hasNext()).isFalse();
    assertThat(exact.nextCursor()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "51", "-1", "bad", "1.5"})
  void invalidPageSizeIsFieldError(String size) throws Exception {
    mvc.perform(get(path()).param("size", size).with(authentication(login(owner))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("size"));
  }

  @Test
  void rejectsMalformedTamperedOrDifferentSpaceCursorAndRechecksAccess() {
    create(member, "하나");
    create(member, "둘");
    String cursor = queries.list(member, space.spaceId(), null, 1).nextCursor();
    var other = spaces.create(owner, new CreateCareSpaceRequest("아빠", UUID.randomUUID()));
    assertCode(
        () -> queries.list(owner, other.spaceId(), cursor, 1), CommonErrorCode.INVALID_REQUEST);
    String decoded =
        new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
    String changed =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                decoded.replace("v1|", "v2|").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String signatureChanged =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                (decoded.substring(0, decoded.length() - 1) + (decoded.endsWith("a") ? "b" : "a"))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    for (String bad :
        List.of("", "garbage", "a".repeat(401), cursor + "=", changed, signatureChanged)) {
      assertCode(
          () -> queries.list(member, space.spaceId(), bad, 1), CommonErrorCode.INVALID_REQUEST);
    }
    end(memberMembership);
    assertCode(() -> queries.list(member, space.spaceId(), cursor, 1), CommonErrorCode.FORBIDDEN);
  }

  @Test
  void sameRequestInFlightReturnsConflictThenReplaysCommittedResult() throws Exception {
    var request = request("동시 저장");
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .execute(
                          tx -> {
                            assertThat(receipts.tryLock(member, request.clientRequestId()))
                                .isTrue();
                            locked.countDown();
                            await(release);
                            return creation.create(member, space.spaceId(), request);
                          }));
      try {
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        postRecord(member, request.content(), request.clientRequestId())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));
      } finally {
        release.countDown();
      }
      var committed = first.get(10, TimeUnit.SECONDS);
      assertThat(creation.create(member, space.spaceId(), request)).isEqualTo(committed);
      assertThat(recordCount()).isEqualTo(1);
    }
  }

  @Test
  void writeWaitingForMembershipEndMustRecheckAndNotPersist() throws Exception {
    CountDownLatch ended = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch saving = new CountDownLatch(1);
    var request = request("이탈과 경합");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var departing =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          tx -> {
                            access.requireActiveMember(member, space.spaceId());
                            end(memberMembership);
                            ended.countDown();
                            await(release);
                          }));
      assertThat(ended.await(10, TimeUnit.SECONDS)).isTrue();
      var writer =
          executor.submit(
              () -> {
                saving.countDown();
                assertCode(
                    () -> creation.create(member, space.spaceId(), request),
                    CommonErrorCode.FORBIDDEN);
              });
      try {
        assertThat(saving.await(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
      }
      departing.get(10, TimeUnit.SECONDS);
      writer.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
    }
    assertThat(recordCount()).isZero();
    assertThat(receiptCount(member, request.clientRequestId())).isZero();
  }

  @Test
  void swaggerIncludesOnlyImplementedPathsAndHonestAnalysisSchema() throws Exception {
    var result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
    var doc = mapper.readTree(result.getResponse().getContentAsString());
    var path = doc.at("/paths/~1api~1spaces~1{spaceId}~1source-records");
    assertThat(path.has("post")).isTrue();
    assertThat(path.has("get")).isTrue();
    assertThat(path.has("delete")).isFalse();
    assertThat(path.at("/post/security").size()).isEqualTo(1);
    assertThat(path.at("/post/security/0").has("CsrfToken")).isTrue();
    assertThat(path.at("/post/security/0").has("SessionCookie")).isTrue();
    assertThat(path.at("/post/parameters").toString()).doesNotContain("currentUser", "userId");
    assertThat(
            doc.at(
                    "/components/responses/CREATE_SOURCE_RECORD_CONFLICT/content/application~1json/examples")
                .size())
        .isEqualTo(2);
    assertThat(doc.at("/components/schemas/SourceRecordDetail/properties/version/type").asText())
        .isEqualTo("string");
    var analysisSchema =
        doc.at("/components/schemas/CreateSourceRecordResponse/properties/analysis");
    assertThat(analysisSchema.isMissingNode()).isFalse();
    assertThat(analysisSchema.toString()).contains("AnalysisSummary");
    assertThat(analysisSchema.path("writeOnly").asBoolean()).isFalse();
    assertThat(
            doc.at("/components/schemas/SourceRecordSummary/properties/latestAnalysis")
                .isMissingNode())
        .isFalse();
    assertThat(
            doc.at("/components/schemas/SourceRecordDetail/properties/latestAnalysis")
                .isMissingNode())
        .isFalse();
  }

  private String path() {
    return "/api/spaces/" + space.spaceId() + "/source-records";
  }

  private CreateSourceRecordRequest request(String content) {
    return new CreateSourceRecordRequest(content, UUID.randomUUID());
  }

  private CreateSourceRecordResponse create(UUID user, String content) {
    return creation.create(user, space.spaceId(), request(content));
  }

  private ResultActions postRecord(UUID user, String content, UUID key) throws Exception {
    return mvc.perform(
        post(path())
            .with(authentication(login(user)))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(new CreateSourceRecordRequest(content, key))));
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

  private UUID join(UUID user) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO space_membership (id, space_id, user_id, joined_at) VALUES (?, ?, ?, now())",
        id,
        space.spaceId(),
        user);
    return id;
  }

  private void end(UUID membership) {
    jdbc.update(
        "UPDATE space_membership SET ended_at = now(), end_reason = 'LEFT' WHERE id = ?",
        membership);
  }

  private void erase(UUID id) {
    jdbc.update(
        "UPDATE source_record SET lifecycle = 'ERASED', body = NULL, erased_at = now(), version = version + 1 WHERE id = ?",
        id);
  }

  private long recordCount() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM source_record WHERE space_id = ?", Long.class, space.spaceId());
  }

  private long receiptCount(UUID actor, UUID key) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM command_receipt WHERE actor_user_id = ? AND client_request_id = ?",
        Long.class,
        actor,
        key);
  }

  private OAuth2AuthenticationToken login(UUID userId) {
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    var principal = new DefaultOAuth2User(authorities, Map.of("id", userId.toString()), "id");
    return new OAuth2AuthenticationToken(
        new MemberOAuth2User(userId, principal), authorities, "kakao");
  }

  private static void assertCode(Runnable action, CommonErrorCode code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
