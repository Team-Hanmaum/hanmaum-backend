package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hanmaum.backend.ai.client.v2.*;
import com.hanmaum.backend.ai.dto.CareItemType;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisResponse;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisResponse.*;
import com.hanmaum.backend.analysis.service.*;
import com.hanmaum.backend.careitem.dto.*;
import com.hanmaum.backend.careitem.service.*;
import com.hanmaum.backend.carespace.dto.*;
import com.hanmaum.backend.carespace.service.*;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.security.oauth.MemberOAuth2User;
import com.hanmaum.backend.proposal.dto.*;
import com.hanmaum.backend.proposal.service.*;
import com.hanmaum.backend.record.dto.*;
import com.hanmaum.backend.record.service.SourceRecordCreationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
    properties = {
      "app.analysis.contract-version=v2",
      "app.analysis.worker.enabled=false",
      "app.ai.v2.max-request-bytes=1048576",
      "app.ai.v2.max-response-bytes=524288",
      "spring.datasource.hikari.maximum-pool-size=4"
    })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProposalDecisionIntegrationTests {
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc mvc;
  @Autowired CareSpaceCreationService spaces;
  @Autowired CareItemCreationService direct;
  @Autowired CareItemQueryService itemQueries;
  @Autowired SourceRecordCreationService records;
  @Autowired AnalysisExecutionService execution;
  @Autowired ProposalQueryService queries;
  @Autowired ProposalDecisionService decisions;
  @Autowired EditLockService locks;
  @Autowired MembershipLifecycleService membershipChanges;
  @Autowired com.hanmaum.backend.analysis.service.AnalysisQueryService analysisQueries;
  @Autowired PlatformTransactionManager transactions;
  @MockitoSpyBean Clock clock;
  UUID owner, member, space, ownerMembership, memberMembership;

  @BeforeEach
  void setup() {
    owner = user();
    member = user();
    var created = spaces.create(owner, new CreateCareSpaceRequest("합성 검토 공간", UUID.randomUUID()));
    space = created.spaceId();
    ownerMembership =
        jdbc.queryForObject(
            "SELECT owner_membership_id FROM care_space WHERE id=?", UUID.class, space);
    memberMembership = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO space_membership(id,space_id,user_id,joined_at) VALUES (?,?,?,now())",
        memberMembership,
        space,
        member);
  }

  @Test
  void createsFiveTypesWithEditedValuesAndPreservesOriginalProposals() throws Exception {
    List<Spec> specs =
        Arrays.stream(CareItemType.values())
            .map(t -> new Spec("CREATE", t.name(), null, Map.of("title", "AI 제목"), List.of()))
            .toList();
    var source = propose(specs);
    var ids = ids(source);
    var body = batch();
    for (int i = 0; i < ids.size(); i++)
      body.withArray("entries")
          .add(entry(ids.get(i), null, createValues(specs.get(i).type, "소유자 확정 " + i)));
    var http =
        mvc.perform(
                post(base() + "/proposal-applications")
                    .with(authentication(login(owner)))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.results.length()").value(5))
            .andReturn();
    var results = mapper.readTree(http.getResponse().getContentAsString()).at("/data/results");
    for (int i = 0; i < 5; i++) {
      UUID item = UUID.fromString(results.get(i).path("itemId").asText());
      assertThat(itemQueries.detail(member, space, item).title()).isEqualTo("소유자 확정 " + i);
      assertThat(itemQueries.detail(owner, space, item).version()).isEqualTo("1");
      var p = queries.detail(owner, space, ids.get(i));
      assertThat(p.status()).isEqualTo(ProposalStatus.APPLIED);
      assertThat(p.reviewToken()).isNull();
      assertThat(p.proposedValues().title()).isEqualTo("AI 제목");
      assertThat(p.decision().appliedValues().path("title").asText()).isEqualTo("소유자 확정 " + i);
      assertThat(count("care_item_history", "item_id", item)).isEqualTo(1);
      assertThat(count("care_item_basis", "item_id", item)).isEqualTo(1);
    }
    assertThat(queries.list(owner, space, null, "APPLIED", null, 20).items()).hasSize(5);
    var replay = decisions.apply(owner, space, body);
    assertThat(replay.results()).hasSize(5);
    assertThat(count("care_item", "space_id", space)).isEqualTo(5);
    String receipt =
        jdbc.queryForObject(
            "SELECT result_refs::text FROM command_receipt WHERE actor_user_id=? AND client_request_id=?",
            String.class,
            owner,
            UUID.fromString(body.path("clientRequestId").asText()));
    assertThat(receipt).doesNotContain("소유자", "AI 제목", "leaseToken", "reviewToken");
    for (var submitted : body.path("entries"))
      assertThat(receipt).doesNotContain(submitted.path("reviewToken").asText());
  }

  @Test
  void validatesWholeBatchBeforeChangingSharedInputVersions() {
    UUID first = seedItem("SCHEDULE"), second = seedItem("TASK");
    var source =
        propose(
            List.of(
                new Spec(
                    "UPDATE", "SCHEDULE", first, Map.of("scheduledDate", "2026-10-25"), List.of()),
                new Spec("UPDATE", "TASK", second, Map.of("dueDate", "2026-10-25"), List.of()),
                new Spec("CREATE", "QUESTION", null, Map.of("title", "미선택 질문"), List.of())));
    var ids = ids(source);
    var body = batch();
    body.withArray("entries")
        .add(
            entry(
                ids.get(0),
                first,
                mapper.readTree("{\"fields\":{\"scheduledDate\":\"2026-10-25\"}}")));
    body.withArray("entries")
        .add(
            entry(
                ids.get(1), second, mapper.readTree("{\"fields\":{\"dueDate\":\"2026-10-25\"}}")));
    var result = decisions.apply(owner, space, body);
    assertThat(result.results()).hasSize(2);
    String receipt =
        jdbc.queryForObject(
            "SELECT result_refs::text FROM command_receipt WHERE actor_user_id=? AND client_request_id=?",
            String.class,
            owner,
            UUID.fromString(body.path("clientRequestId").asText()));
    for (var submitted : body.path("entries"))
      assertThat(receipt)
          .doesNotContain(
              submitted.path("reviewToken").asText(), submitted.path("leaseToken").asText());
    assertThat(itemQueries.detail(owner, space, first).fields().path("scheduledDate").asText())
        .isEqualTo("2026-10-25");
    assertThat(itemQueries.detail(owner, space, second).fields().path("dueDate").asText())
        .isEqualTo("2026-10-25");
    assertThat(itemQueries.detail(owner, space, first).editLock()).isNull();
    assertThat(queries.detail(owner, space, ids.get(0)).status()).isEqualTo(ProposalStatus.APPLIED);
    assertThat(queries.detail(owner, space, ids.get(0)).decision().appliedValues()).isNotNull();
    assertThat(queries.detail(owner, space, ids.get(2)).status())
        .isEqualTo(ProposalStatus.INVALIDATED);
    assertThat(count("care_item", "space_id", space)).isEqualTo(2);
    assertThat(decisions.apply(owner, space, body)).isEqualTo(result);
    assertThat(
            analysisQueries
                .find(owner, space, source.recordId(), source.analysis().analysisId())
                .proposalIds())
        .containsExactly(ids.get(0), ids.get(1));
  }

  @Test
  void lastInvalidEntryRollsBackAllChangesAndKeepsLeases() {
    UUID first = seedItem("SCHEDULE"), second = seedItem("TASK");
    var source = propose(List.of(update(first, "SCHEDULE"), update(second, "TASK")));
    var ids = ids(source);
    var body = batch();
    body.withArray("entries").add(entry(ids.get(0), first, mapper.readTree("{\"title\":\"변경\"}")));
    body.withArray("entries")
        .add(
            entry(
                ids.get(1), second, mapper.readTree("{\"fields\":{\"dueDate\":\"2026-02-30\"}}")));
    failure(() -> decisions.apply(owner, space, body), "INVALID_REQUEST");
    assertThat(itemQueries.detail(owner, space, first).version()).isEqualTo("1");
    assertThat(itemQueries.detail(owner, space, first).editLock()).isNotNull();
    assertThat(count("proposal_decision_batch", "space_id", space)).isZero();
    assertThat(count("care_item_history", "item_id", first)).isEqualTo(1);
    assertThat(queries.detail(owner, space, ids.get(0)).status())
        .isEqualTo(ProposalStatus.REVIEW_PENDING);
  }

  @Test
  void resolvesQuestionAndSuppliesOnlyConfirmedAnswerToSubsequentAnalysis() {
    UUID item = seedItem("QUESTION");
    var source =
        propose(
            List.of(
                new Spec("RESOLVE", "QUESTION", item, Map.of("answerText", "AI 답변"), List.of())));
    var body = batch();
    body.withArray("entries")
        .add(
            entry(
                ids(source).getFirst(),
                item,
                mapper.readTree("{\"confirmedAnswer\":\"소유자가 확인한 답변\"}")));
    decisions.apply(owner, space, body);
    var detail = itemQueries.detail(member, space, item);
    assertThat(detail.confirmedAnswer()).isEqualTo("소유자가 확인한 답변");
    assertThat(detail.status()).isEqualTo("CONFIRMED");
    var next =
        records.create(owner, space, new CreateSourceRecordRequest("다음 합성 소식", UUID.randomUUID()));
    var work =
        execution.claim(space, next.analysis().analysisId(), Duration.ofMinutes(2)).orElseThrow();
    var candidate = mapper.valueToTree(work.v2Request()).path("candidates").get(0);
    assertThat(candidate.at("/currentValues/answerText").asText()).isEqualTo("소유자가 확인한 답변");
  }

  @Test
  void linksEvidenceWithoutReplacingExistingContentAndDeclinesWithoutChangingItems() {
    UUID item = seedItem("TASK");
    var source =
        propose(
            List.of(
                new Spec("LINK_EVIDENCE", "TASK", item, Map.of(), List.of()),
                new Spec("CREATE", "GUIDANCE", null, Map.of("title", "안내"), List.of())));
    var ids = ids(source);
    var decline = batch();
    decline
        .withArray("entries")
        .add(
            mapper
                .createObjectNode()
                .put("proposalId", ids.get(1).toString())
                .put("reviewToken", queries.detail(owner, space, ids.get(1)).reviewToken()));
    var declined = decisions.decline(owner, space, decline);
    assertThat(decisions.decline(owner, space, decline)).isEqualTo(declined);
    assertThat(itemQueries.detail(owner, space, item).version()).isEqualTo("1");
    var before = itemQueries.detail(owner, space, item);
    var body = batch();
    body.withArray("entries").add(entry(ids.get(0), item, null));
    decisions.apply(owner, space, body);
    var after = itemQueries.detail(owner, space, item);
    assertThat(after.title()).isEqualTo(before.title());
    assertThat(after.fields()).isEqualTo(before.fields());
    assertThat(after.version()).isEqualTo("2");
    assertThat(count("care_item_basis", "item_id", item)).isEqualTo(2);
    assertThat(count("care_item", "space_id", space)).isEqualTo(1);
    assertThat(queries.detail(owner, space, ids.get(1)).status())
        .isEqualTo(ProposalStatus.DECLINED);
  }

  @Test
  void confirmedQuestionAllowsTitleAndDescriptionChangesButNotAnswerOrStatusReplacement() {
    UUID item = seedItem("QUESTION");
    var resolve =
        propose(
            List.of(
                new Spec("RESOLVE", "QUESTION", item, Map.of("answerText", "AI 답변"), List.of())));
    var first = batch();
    first
        .withArray("entries")
        .add(
            entry(
                ids(resolve).getFirst(),
                item,
                mapper.readTree("{\"confirmedAnswer\":\"확정된 답변\"}")));
    decisions.apply(owner, space, first);

    var update = propose(List.of(update(item, "QUESTION")));
    var body = batch();
    var e = entry(ids(update).getFirst(), item, mapper.readTree("{\"confirmedAnswer\":\"다른 답변\"}"));
    body.withArray("entries").add(e);
    failure(() -> decisions.apply(owner, space, body), "INVALID_REQUEST");
    e.set("values", mapper.readTree("{\"status\":\"UNCONFIRMED\"}"));
    failure(() -> decisions.apply(owner, space, body), "PROPOSAL_STATE_CONFLICT");
    e.set("values", mapper.readTree("{\"title\":\"질문 제목 정정\",\"description\":null}"));
    decisions.apply(owner, space, body);
    var actual = itemQueries.detail(owner, space, item);
    assertThat(actual.title()).isEqualTo("질문 제목 정정");
    assertThat(actual.description()).isNull();
    assertThat(actual.confirmedAnswer()).isEqualTo("확정된 답변");
    assertThat(actual.status()).isEqualTo("CONFIRMED");
    assertThat(actual.version()).isEqualTo("3");
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"title\":\"제목만 변경\"}", "{\"status\":\"IN_PROGRESS\"}"})
  void omittedInactiveAssigneeRemainsStoredButHiddenUntilExplicitlyCleared(String changes) {
    UUID item = seedItem("TASK");
    jdbc.update(
        "UPDATE content_document SET body=jsonb_set(body,'{fields,assigneeMembershipId}',to_jsonb(CAST(? AS text))) WHERE id=(SELECT current_document_id FROM care_item WHERE id=?)",
        memberMembership.toString(),
        item);
    jdbc.update(
        "UPDATE space_membership SET ended_at=now(),end_reason='LEFT' WHERE id=?",
        memberMembership);
    var source = propose(List.of(update(item, "TASK")));
    var body = batch();
    body.withArray("entries").add(entry(ids(source).getFirst(), item, mapper.readTree(changes)));
    decisions.apply(owner, space, body);
    assertThat(
            jdbc.queryForObject(
                "SELECT d.body #>> '{fields,assigneeMembershipId}' FROM care_item i JOIN content_document d ON d.id=i.current_document_id WHERE i.id=?",
                String.class,
                item))
        .isEqualTo(memberMembership.toString());
    assertThat(
            itemQueries.detail(owner, space, item).fields().path("assigneeMembershipId").isNull())
        .isTrue();

    var next = propose(List.of(update(item, "TASK")));
    var clear = batch();
    clear
        .withArray("entries")
        .add(
            entry(
                ids(next).getFirst(),
                item,
                mapper.readTree("{\"fields\":{\"assigneeMembershipId\":null}}")));
    decisions.apply(owner, space, clear);
    assertThat(
            jdbc.queryForObject(
                "SELECT d.body #>> '{fields,assigneeMembershipId}' FROM care_item i JOIN content_document d ON d.id=i.current_document_id WHERE i.id=?",
                String.class,
                item))
        .isNull();
  }

  @Test
  void leasesUseDatabaseTimeEvenWhenApplicationClockIsAheadOrBehind() {
    UUID item = seedItem("TASK");
    Instant before =
        jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
    try {
      org.mockito.Mockito.doReturn(before.plus(Duration.ofDays(7))).when(clock).instant();
      var lease = locks.acquire(owner, space, item, 1);
      Instant after =
          jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
      assertThat(lease.expiresAt())
          .isBetween(before.plus(Duration.ofMinutes(2)), after.plus(Duration.ofMinutes(2)));
      org.mockito.Mockito.doReturn(before.minus(Duration.ofDays(7))).when(clock).instant();
      assertThat(itemQueries.detail(owner, space, item).editLock()).isNotNull();
      failure(() -> locks.acquire(member, space, item, 1), "ITEM_LOCKED");
      assertThat(locks.renew(owner, space, item, lease.leaseToken()).expiresAt())
          .isAfter(lease.expiresAt());
      jdbc.update(
          "UPDATE care_item_edit_lock SET expires_at=clock_timestamp()-interval '1 second' WHERE item_id=?",
          item);
      assertThat(itemQueries.detail(owner, space, item).editLock()).isNull();
      failure(() -> locks.renew(owner, space, item, lease.leaseToken()), "EDIT_LOCK_EXPIRED");
      assertThat(locks.acquire(member, space, item, 1).leaseToken()).isNotBlank();
    } finally {
      org.mockito.Mockito.reset(clock);
    }
  }

  @Test
  void rejectsTwoProposalsForOneTargetAndActionOrTypeChanges() {
    UUID item = seedItem("TASK");
    var source = propose(List.of(update(item, "TASK"), update(item, "TASK")));
    var ids = ids(source);
    var first = entry(ids.get(0), item, mapper.readTree("{\"title\":\"수정\"}"));
    var second =
        first
            .deepCopy()
            .put("proposalId", ids.get(1).toString())
            .put("reviewToken", queries.detail(owner, space, ids.get(1)).reviewToken());
    var body = batch();
    body.withArray("entries").add(first).add(second);
    failure(() -> decisions.apply(owner, space, body), "PROPOSAL_BATCH_CONFLICT");
    body.withArray("entries").remove(1);
    first.put("action", "CREATE_ITEM");
    failure(() -> decisions.apply(owner, space, body), "INVALID_REQUEST");
  }

  @Test
  void exactRetrySurvivesRedactionButDifferentContentAndChangedOwnerAreRejected() {
    var source =
        propose(List.of(new Spec("CREATE", "QUESTION", null, Map.of("title", "질문"), List.of())));
    var body = batch();
    body.withArray("entries")
        .add(entry(ids(source).getFirst(), null, createValues("QUESTION", "민감 확정 제목")));
    var result = decisions.apply(owner, space, body);
    erase(source);
    assertThat(decisions.apply(owner, space, body)).isEqualTo(result);
    assertThat(queries.detail(owner, space, ids(source).getFirst()).status())
        .isEqualTo(ProposalStatus.APPLIED);
    assertThat(queries.detail(owner, space, ids(source).getFirst()).decision().appliedValues())
        .isNull();
    var changed = body.deepCopy();
    ((ObjectNode) changed.at("/entries/0/values")).put("title", "다른 내용");
    failure(() -> decisions.apply(owner, space, changed), "IDEMPOTENCY_KEY_REUSED");
    jdbc.update(
        "UPDATE care_space SET owner_membership_id=?,owner_user_id=? WHERE id=?",
        memberMembership,
        member,
        space);
    failure(() -> decisions.apply(owner, space, body), "FORBIDDEN");
  }

  @Test
  void sourceDeletionRedactsCurrentAndHistoricalDerivedCopiesWithoutErasingIndependentOriginal() {
    UUID item = seedItem("SCHEDULE");
    UUID original =
        jdbc.queryForObject(
            "SELECT current_document_id FROM care_item WHERE id=?", UUID.class, item);
    var source = propose(List.of(update(item, "SCHEDULE")));
    var body = batch();
    body.withArray("entries")
        .add(entry(ids(source).getFirst(), item, mapper.readTree("{\"title\":\"민감 수정 제목\"}")));
    decisions.apply(owner, space, body);
    UUID applied =
        jdbc.queryForObject(
            "SELECT current_document_id FROM care_item WHERE id=?", UUID.class, item);
    erase(source);
    assertThat(
            jdbc.queryForObject(
                "SELECT body::text FROM content_document WHERE id=?", String.class, applied))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT body::text FROM content_document WHERE id=?", String.class, original))
        .contains("기존 제목");
    assertThat(itemQueries.detail(owner, space, item).title()).isNull();
    assertThat(itemQueries.detail(owner, space, item).needsReconfirmation()).isTrue();
    assertThat(queries.detail(owner, space, ids(source).getFirst()).proposedValues()).isNull();
  }

  @Test
  void readGuardHidesDerivedContentBeforePhysicalDeletionPropagation() {
    var source =
        propose(List.of(new Spec("CREATE", "QUESTION", null, Map.of("title", "질문"), List.of())));
    var body = batch();
    body.withArray("entries")
        .add(entry(ids(source).getFirst(), null, createValues("QUESTION", "민감 내용")));
    UUID item = decisions.apply(owner, space, body).results().getFirst().itemId();
    jdbc.update(
        "UPDATE source_record SET body=NULL,lifecycle='ERASED',erased_at=now(),version=version+1 WHERE id=?",
        source.recordId());
    assertThat(itemQueries.detail(owner, space, item).title()).isNull();
    assertThat(queries.detail(owner, space, ids(source).getFirst()).decision().appliedValues())
        .isNull();
  }

  @Test
  void leasesAreExclusiveRenewableOpaqueAndDoNotGrantOwnerAuthority() throws Exception {
    UUID item = seedItem("SCHEDULE");
    var lease = locks.acquire(member, space, item, 1);
    assertThat(itemQueries.detail(owner, space, item).editLock().holderMembershipId())
        .isEqualTo(memberMembership);
    failure(() -> locks.acquire(owner, space, item, 1), "ITEM_LOCKED");
    failure(() -> locks.acquire(member, space, item, 1), "ITEM_LOCKED");
    var renewed = locks.renew(member, space, item, lease.leaseToken());
    assertThat(renewed.expiresAt()).isAfter(lease.expiresAt());
    assertThat(renewed.leaseToken()).isNull();
    assertThat(
            jdbc.queryForObject(
                    "SELECT expires_at FROM care_item_edit_lock WHERE item_id=?",
                    java.sql.Timestamp.class,
                    item)
                .toInstant())
        .isEqualTo(renewed.expiresAt());
    assertThat(
            jdbc.queryForObject(
                "SELECT token_digest FROM care_item_edit_lock WHERE item_id=?", String.class, item))
        .doesNotContain(lease.leaseToken());
    var source = propose(List.of(update(item, "SCHEDULE")));
    var body = batch();
    var id = ids(source).getFirst();
    body.withArray("entries")
        .add(
            mapper
                .createObjectNode()
                .put("proposalId", id.toString())
                .put("action", "UPDATE_ITEM")
                .put("reviewToken", queries.detail(member, space, id).reviewToken())
                .put("targetItemId", item.toString())
                .put("expectedItemVersion", "1")
                .put("leaseToken", lease.leaseToken())
                .set("values", mapper.readTree("{\"title\":\"수정\"}")));
    mvc.perform(
            post(base() + "/proposal-applications")
                .with(authentication(login(member)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isForbidden());
    locks.release(member, space, item, lease.leaseToken());
    assertThat(itemQueries.detail(owner, space, item).editLock()).isNull();
  }

  @Test
  void expiredOrReplacedLeaseCannotBeRenewedOrReleaseNewLease() {
    UUID item = seedItem("TASK");
    var old = locks.acquire(owner, space, item, 1);
    jdbc.update(
        "UPDATE care_item_edit_lock SET expires_at=now()-interval '1 second' WHERE item_id=?",
        item);
    failure(() -> locks.renew(owner, space, item, old.leaseToken()), "EDIT_LOCK_EXPIRED");
    var fresh = locks.acquire(owner, space, item, 1);
    failure(() -> locks.release(owner, space, item, old.leaseToken()), "EDIT_LOCK_EXPIRED");
    assertThat(itemQueries.detail(owner, space, item).editLock()).isNotNull();
    locks.release(owner, space, item, fresh.leaseToken());
    locks.release(owner, space, item, fresh.leaseToken());
  }

  @Test
  void twoConcurrentApplicationsCreateExactlyOneItem() throws Exception {
    var source =
        propose(List.of(new Spec("CREATE", "TASK", null, Map.of("title", "할 일"), List.of())));
    var body = batch();
    body.withArray("entries").add(entry(ids(source).getFirst(), null, createValues("TASK", "확정")));
    var other = body.deepCopy().put("clientRequestId", UUID.randomUUID().toString());
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> concurrent(start, body));
      var b = pool.submit(() -> concurrent(start, other));
      start.countDown();
      assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("OK", "PROPOSAL_STATE_CONFLICT");
    }
    assertThat(count("care_item", "space_id", space)).isEqualTo(1);
  }

  @Test
  void csrfAuthenticationAndUnknownFieldsAreRejected() throws Exception {
    mvc.perform(
            post(base() + "/proposal-applications")
                .with(authentication(login(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(base() + "/proposal-applications")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post(base() + "/proposal-declines")
                .with(authentication(login(owner)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entries\":[],\"unknown\":1}"))
        .andExpect(status().isBadRequest());
  }

  private String concurrent(CountDownLatch start, JsonNode body) throws Exception {
    start.await();
    try {
      decisions.apply(owner, space, body);
      return "OK";
    } catch (ApiException e) {
      return e.code().code();
    }
  }

  @Test
  void ambiguousTargetsOnlyAllowCapturedCandidatesAndNotFoundCannotBeInvented() {
    UUID a = seedItem("TASK"), b = seedItem("TASK"), outside = seedItem("TASK");
    var source =
        propose(
            List.of(
                new Spec("UPDATE", "TASK", null, Map.of("title", "AI 변경"), List.of(a, b)),
                new Spec("UPDATE", "TASK", null, Map.of("title", "미상"), List.of())));
    var ids = ids(source);
    var body = batch();
    var e = entry(ids.get(0), a, mapper.readTree("{\"title\":\"선택한 할 일\"}"));
    body.withArray("entries").add(e);
    e.put("targetItemId", outside.toString());
    failure(() -> decisions.apply(owner, space, body), "INVALID_REQUEST");
    e.put("targetItemId", a.toString());
    e.put("proposalId", ids.get(1).toString());
    e.put("reviewToken", queries.detail(owner, space, ids.get(1)).reviewToken());
    failure(() -> decisions.apply(owner, space, body), "INVALID_REQUEST");
    e.put("proposalId", ids.get(0).toString());
    e.put("reviewToken", queries.detail(owner, space, ids.get(0)).reviewToken());
    decisions.apply(owner, space, body);
    assertThat(itemQueries.detail(owner, space, a).title()).isEqualTo("선택한 할 일");
    assertThat(itemQueries.detail(owner, space, b).version()).isEqualTo("1");
  }

  @Test
  void omittedValuesRemainExplicitNullClearsAndNoOpIsRejected() {
    UUID item = seedItem("TASK");
    var source = propose(List.of(update(item, "TASK")));
    var body = batch();
    var e = entry(ids(source).getFirst(), item, mapper.createObjectNode());
    body.withArray("entries").add(e);
    failure(() -> decisions.apply(owner, space, body), "INVALID_REQUEST");
    e.set(
        "values",
        mapper.readTree("{\"description\":null,\"fields\":{\"assigneeMembershipId\":null}}"));
    decisions.apply(owner, space, body);
    var detail = itemQueries.detail(owner, space, item);
    assertThat(detail.title()).isEqualTo("기존 제목");
    assertThat(detail.description()).isNull();
    assertThat(detail.fields().path("assigneeMembershipId").isNull()).isTrue();
  }

  @Test
  void rejectsWrongReviewExpiredLeaseStaleVersionAndInactiveAssignee() {
    UUID item = seedItem("TASK");
    var source = propose(List.of(update(item, "TASK")));
    UUID p = ids(source).getFirst();
    var body = batch();
    var e = entry(p, item, mapper.readTree("{\"title\":\"변경\"}"));
    body.withArray("entries").add(e);
    String review = e.path("reviewToken").asText();
    e.put("reviewToken", queries.detail(member, space, p).reviewToken());
    failure(() -> decisions.apply(owner, space, body), "PROPOSAL_STATE_CONFLICT");
    e.put("reviewToken", review);
    e.put("expectedItemVersion", "2");
    failure(() -> decisions.apply(owner, space, body), "VERSION_CONFLICT");
    e.put("expectedItemVersion", "1");
    jdbc.update(
        "UPDATE space_membership SET ended_at=now(),end_reason='LEFT' WHERE id=?",
        memberMembership);
    e.set(
        "values",
        mapper
            .createObjectNode()
            .set(
                "fields",
                mapper
                    .createObjectNode()
                    .put("assigneeMembershipId", memberMembership.toString())));
    failure(() -> decisions.apply(owner, space, body), "INVALID_REQUEST");
    e.set("values", mapper.readTree("{\"title\":\"변경\"}"));
    jdbc.update(
        "UPDATE care_item_edit_lock SET expires_at=now()-interval '1 second' WHERE item_id=?",
        item);
    failure(() -> decisions.apply(owner, space, body), "EDIT_LOCK_EXPIRED");
    assertThat(itemQueries.detail(owner, space, item).version()).isEqualTo("1");
  }

  @Test
  void databaseFailureAfterFirstSaveRollsBackEveryWrite() {
    var source =
        propose(
            List.of(
                new Spec("CREATE", "TASK", null, Map.of("title", "하나"), List.of()),
                new Spec("CREATE", "QUESTION", null, Map.of("title", "둘"), List.of())));
    var ids = ids(source);
    var body = batch();
    body.withArray("entries")
        .add(entry(ids.get(0), null, createValues("TASK", "하나")))
        .add(entry(ids.get(1), null, createValues("QUESTION", "둘")));
    jdbc.execute(
        "CREATE FUNCTION test_decision_save_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.ordinal_no=1 THEN RAISE EXCEPTION 'synthetic storage failure'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER test_decision_save_failure BEFORE INSERT ON proposal_decision FOR EACH ROW EXECUTE FUNCTION test_decision_save_failure()");
    try {
      assertThatThrownBy(() -> decisions.apply(owner, space, body))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(count("care_item", "space_id", space)).isZero();
      assertThat(count("proposal_decision_batch", "space_id", space)).isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM command_receipt WHERE actor_user_id=? AND client_request_id=?",
                  Long.class,
                  owner,
                  UUID.fromString(body.path("clientRequestId").asText())))
          .isZero();
      assertThat(queries.detail(owner, space, ids.getFirst()).status())
          .isEqualTo(ProposalStatus.REVIEW_PENDING);
    } finally {
      jdbc.execute("DROP TRIGGER test_decision_save_failure ON proposal_decision");
      jdbc.execute("DROP FUNCTION test_decision_save_failure()");
    }
    assertThat(decisions.apply(owner, space, body).results()).hasSize(2);
  }

  @Test
  void directDocumentErasureRemovesConfirmedAnswerAndDownstreamCopiedProposals() {
    UUID question = seedItem("QUESTION");
    UUID original =
        jdbc.queryForObject(
            "SELECT current_document_id FROM care_item WHERE id=?", UUID.class, question);
    var first =
        propose(
            List.of(
                new Spec("RESOLVE", "QUESTION", question, Map.of("answerText", "답변"), List.of())));
    var body = batch();
    body.withArray("entries")
        .add(
            entry(
                ids(first).getFirst(),
                question,
                mapper.readTree("{\"confirmedAnswer\":\"확정 답변\"}")));
    decisions.apply(owner, space, body);
    var later =
        propose(List.of(new Spec("CREATE", "TASK", null, Map.of("title", "파생된 내용"), List.of())));
    var historyBefore =
        jdbc.queryForList(
            "SELECT * FROM care_item_history WHERE item_id=? ORDER BY version", question);
    assertThat(historyBefore).hasSize(2);
    var historyDocuments =
        jdbc.queryForList(
            "SELECT document_id FROM care_item_history WHERE item_id=? ORDER BY version",
            UUID.class,
            question);
    assertThat(historyDocuments).hasSize(2).doesNotHaveDuplicates();
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            s -> {
              jdbc.queryForObject(
                  "SELECT id FROM care_space WHERE id=? FOR UPDATE", UUID.class, space);
              jdbc.update(
                  "UPDATE content_document SET body=NULL,redacted_at=now() WHERE id=?", original);
              execution.invalidateDirectDocument(space, original);
            });
    assertThat(itemQueries.detail(owner, space, question).confirmedAnswer()).isNull();
    assertThat(queries.detail(owner, space, ids(later).getFirst()).proposedValues()).isNull();
    assertThat(
            jdbc.queryForList(
                "SELECT * FROM care_item_history WHERE item_id=? ORDER BY version", question))
        .containsExactlyElementsOf(historyBefore);
    for (UUID document : historyDocuments) {
      var erased =
          jdbc.queryForMap("SELECT body,redacted_at FROM content_document WHERE id=?", document);
      assertThat(erased.get("body")).isNull();
      assertThat(erased.get("redacted_at")).isNotNull();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content_document d JOIN care_item_history h ON h.document_id=d.id WHERE h.item_id=? AND d.body IS NOT NULL",
                Long.class,
                question))
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 21})
  void rejectsBatchSizeOutsideContract(int size) {
    var body = batch();
    for (int i = 0; i < size; i++) body.withArray("entries").add(mapper.createObjectNode());
    failure(() -> decisions.apply(owner, space, body), "INVALID_REQUEST");
  }

  @ParameterizedTest
  @ValueSource(strings = {"proposal-applications", "proposal-declines"})
  void rejectsOversizedHttpBodyBeforeParsingJson(String endpoint) throws Exception {
    String malformed = "[" + " ".repeat(CareMutationSizeLimits.REQUEST_BYTES);
    mvc.perform(
            post(base() + "/" + endpoint)
                .with(authentication(login(owner)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(malformed))
        .andExpect(status().is(413))
        .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
        .andExpect(jsonPath("$.data").isEmpty());
    assertThat(count("proposal_decision_batch", "space_id", space)).isZero();
    // At the byte boundary, parsing/normal input validation still runs (400, not 413).
    mvc.perform(
            post(base() + "/" + endpoint)
                .with(authentication(login(owner)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}" + " ".repeat(CareMutationSizeLimits.REQUEST_BYTES - 2)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void rejectsTextAboveUtf16LimitAndAcceptsExactBoundaryWithoutTruncation() throws Exception {
    var source =
        propose(List.of(new Spec("CREATE", "QUESTION", null, Map.of("title", "AI 제목"), List.of())));
    String boundary = "😀".repeat(5_000);
    var values = createValues("QUESTION", boundary + "x");
    var body = batch();
    body.withArray("entries").add(entry(ids(source).getFirst(), null, values));
    mvc.perform(
            post(base() + "/proposal-applications")
                .with(authentication(login(owner)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    assertThat(count("care_item", "space_id", space)).isZero();
    assertThat(count("proposal_decision_batch", "space_id", space)).isZero();
    values.put("title", boundary);
    UUID item = decisions.apply(owner, space, body).results().getFirst().itemId();
    assertThat(itemQueries.detail(owner, space, item).title()).isEqualTo(boundary);
  }

  @Test
  void excessiveFinalDocumentBatchRejectsAllWritesEvenWhenEachFieldAndHttpBodyFit()
      throws Exception {
    var specs =
        java.util.stream.IntStream.range(0, 6)
            .mapToObj(
                i ->
                    new Spec("CREATE", "SCHEDULE", null, Map.of("title", "AI 제목"), List.<UUID>of()))
            .toList();
    var source = propose(specs);
    var body = batch();
    String text = "가".repeat(10_000);
    for (UUID id : ids(source)) {
      var values = createValues("SCHEDULE", text).put("description", text);
      values.withObject("fields").put("location", text);
      body.withArray("entries").add(entry(id, null, values));
    }
    byte[] request = mapper.writeValueAsBytes(body);
    assertThat(request.length).isLessThan(CareMutationSizeLimits.REQUEST_BYTES);
    mvc.perform(
            post(base() + "/proposal-applications")
                .with(authentication(login(owner)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    assertThat(count("care_item", "space_id", space)).isZero();
    assertThat(count("proposal_decision_batch", "space_id", space)).isZero();
    assertThat(queries.list(owner, space, null, "REVIEW_PENDING", null, 20).items()).hasSize(6);
  }

  private Spec update(UUID item, String type) {
    return new Spec("UPDATE", type, item, Map.of("title", "AI 변경"), List.of());
  }

  private ObjectNode batch() {
    var b = mapper.createObjectNode().put("clientRequestId", UUID.randomUUID().toString());
    b.putArray("entries");
    return b;
  }

  private ObjectNode createValues(String type, String title) {
    var v =
        mapper.createObjectNode().put("itemType", type).put("title", title).putNull("description");
    v.putObject("fields");
    return v;
  }

  private ObjectNode entry(UUID proposal, UUID item, JsonNode values) {
    var detail = queries.detail(owner, space, proposal);
    var e =
        mapper
            .createObjectNode()
            .put("proposalId", proposal.toString())
            .put("action", detail.action().name())
            .put("reviewToken", detail.reviewToken());
    if (item != null) {
      long version = Long.parseLong(itemQueries.detail(owner, space, item).version());
      e.put("targetItemId", item.toString())
          .put("expectedItemVersion", Long.toString(version))
          .put("leaseToken", locks.acquire(owner, space, item, version).leaseToken());
    }
    e.set("values", values == null ? mapper.nullNode() : values);
    return e;
  }

  private UUID seedItem(String type) {
    var response =
        direct.create(
            owner,
            space,
            new CreateCareItemRequest(
                "SCHEDULE",
                "기존 제목",
                "기존 설명",
                new ScheduleFields(null, null, null),
                UUID.randomUUID()));
    if (!type.equals("SCHEDULE")) {
      String status =
          switch (type) {
            case "TASK" -> "NOT_STARTED";
            case "QUESTION" -> "UNCONFIRMED";
            default -> null;
          };
      jdbc.update(
          "UPDATE care_item SET item_type=?,status=? WHERE id=?", type, status, response.itemId());
      jdbc.update(
          "UPDATE care_item_history SET status=? WHERE item_id=?", status, response.itemId());
      jdbc.update(
          "UPDATE content_document SET body=jsonb_set(body,'{fields}','{}') WHERE id=(SELECT current_document_id FROM care_item WHERE id=?)",
          response.itemId());
    }
    return response.itemId();
  }

  private CreateSourceRecordResponse propose(List<Spec> specs) {
    var source =
        records.create(owner, space, new CreateSourceRecordRequest("합성 원본 소식", UUID.randomUUID()));
    var work =
        execution.claim(space, source.analysis().analysisId(), Duration.ofMinutes(2)).orElseThrow();
    var candidates = work.v2Request().candidates();
    var suggestions = new ArrayList<Suggestion>();
    for (var spec : specs) {
      var target =
          candidates.stream().filter(c -> c.itemId().equals(spec.target)).findFirst().orElse(null);
      var refs = new ArrayList<BasisReference>();
      if (target != null)
        refs.add(new BasisReference(target.itemId(), List.of(target.basis().getFirst().basisId())));
      for (UUID candidate : spec.candidates) {
        var c =
            candidates.stream().filter(x -> x.itemId().equals(candidate)).findFirst().orElseThrow();
        refs.add(new BasisReference(candidate, List.of(c.basis().getFirst().basisId())));
      }
      var relation =
          spec.operation.equals("CREATE")
              ? RelationKind.NEW
              : target == null
                  ? RelationKind.UNDETERMINED
                  : spec.operation.equals("LINK_EVIDENCE")
                      ? RelationKind.DUPLICATE
                      : RelationKind.RELATED;
      List<ReviewReason> reasons =
          target == null && !spec.operation.equals("CREATE")
              ? List.of(
                  spec.candidates.isEmpty()
                      ? ReviewReason.TARGET_NOT_FOUND
                      : ReviewReason.TARGET_AMBIGUOUS)
              : List.of();
      suggestions.add(
          new Suggestion(
              Operation.valueOf(spec.operation),
              CareItemType.valueOf(spec.type),
              spec.target,
              target == null ? null : target.version(),
              spec.candidates,
              "AI 검토 제목",
              "합성 원본 소식",
              spec.changes,
              List.of(),
              relation,
              reasons,
              refs));
    }
    assertThat(
            execution.completeV2(
                work, new AiV2AnalysisResponse(work.v2Request().requestId(), suggestions)))
        .isTrue();
    return source;
  }

  private List<UUID> ids(CreateSourceRecordResponse source) {
    return jdbc.query(
        "SELECT id FROM ai_proposal WHERE run_id=? ORDER BY ordinal_no",
        (rs, n) -> rs.getObject(1, UUID.class),
        source.analysis().analysisId());
  }

  private void erase(CreateSourceRecordResponse source) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            s -> {
              jdbc.queryForObject(
                  "SELECT id FROM care_space WHERE id=? FOR UPDATE", UUID.class, space);
              jdbc.update(
                  "UPDATE source_record SET lifecycle='ERASED',body=NULL,erased_at=now(),version=version+1 WHERE id=?",
                  source.recordId());
              execution.invalidateSource(space, source.recordId());
            });
  }

  private long count(String table, String field, UUID id) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + field + "=?", Long.class, id);
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

  private String base() {
    return "/api/spaces/" + space;
  }

  private static void failure(Runnable work, String code) {
    assertThatThrownBy(work::run)
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code().code()).isEqualTo(code));
  }

  @Test
  void swaggerRequiresSessionAndCsrfTogetherAndDescribesFiveMutationEndpoints() throws Exception {
    var doc =
        mapper.readTree(
            mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    for (String suffix :
        List.of(
            "/proposal-applications",
            "/proposal-declines",
            "/items/{itemId}/edit-lock",
            "/items/{itemId}/edit-lock/renewals",
            "/items/{itemId}/edit-lock/releases")) {
      var op = doc.path("paths").path("/api/spaces/{spaceId}" + suffix).path("post");
      assertThat(op.path("security").size()).isEqualTo(1);
      assertThat(op.path("security").get(0).propertyNames())
          .containsExactlyInAnyOrder("SessionCookie", "CsrfToken");
      assertThat(op.at("/requestBody/required").asBoolean()).isTrue();
      assertThat(op.at("/responses/409/content/application~1json/schema/$ref").asText())
          .isEqualTo("#/components/schemas/ApiResponse");
      assertThat(op.at("/responses/409/content/application~1json/examples").size()).isPositive();
    }
    var acquire =
        doc.path("paths").path("/api/spaces/{spaceId}/items/{itemId}/edit-lock").path("post");
    assertThat(acquire.at("/responses/201/content/application~1json/schema/$ref").asText())
        .contains("EditLockResponse");
    var release =
        doc.path("paths")
            .path("/api/spaces/{spaceId}/items/{itemId}/edit-lock/releases")
            .path("post");
    assertThat(release.at("/responses/204").has("content")).isFalse();
  }

  @Test
  void upgradesV10WithoutChangingExistingContentAndBackfillsDocumentAncestry() {
    UUID item = seedItem("SCHEDULE");
    var source = propose(List.of(update(item, "SCHEDULE")));
    UUID proposal = ids(source).getFirst();
    String schema = "decisions_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var ds = jdbc.getDataSource();
    org.flywaydb.core.Flyway.configure()
        .dataSource(ds)
        .schemas(schema)
        .defaultSchema(schema)
        .target("10")
        .load()
        .migrate();
    var tx = new TransactionTemplate(transactions);
    tx.executeWithoutResult(
        s -> {
          jdbc.execute("SET LOCAL search_path TO " + schema);
          jdbc.update(
              "INSERT INTO app_user SELECT * FROM public.app_user WHERE id IN (?,?)",
              owner,
              member);
          jdbc.update(
              "INSERT INTO command_receipt SELECT * FROM public.command_receipt WHERE actor_user_id=?",
              owner);
          jdbc.update("INSERT INTO care_space SELECT * FROM public.care_space WHERE id=?", space);
          jdbc.update(
              "INSERT INTO space_membership(id,space_id,user_id,joined_at,ended_at,end_reason,invitation_id) SELECT id,space_id,user_id,joined_at,ended_at,end_reason,invitation_id FROM public.space_membership WHERE space_id=?",
              space);
          for (String table :
              List.of(
                  "source_record",
                  "content_document",
                  "evidence",
                  "care_item",
                  "care_item_basis",
                  "care_item_history",
                  "analysis_run",
                  "analysis_input_source",
                  "analysis_input_item",
                  "analysis_input_basis",
                  "ai_proposal"))
            jdbc.update(
                "INSERT INTO " + table + " SELECT * FROM public." + table + " WHERE space_id=?",
                space);
        });
    var flyway =
        org.flywaydb.core.Flyway.configure()
            .dataSource(ds)
            .schemas(schema)
            .defaultSchema(schema)
            .target("11")
            .load();
    flyway.migrate();
    flyway.validate();
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("11");
    tx.executeWithoutResult(
        s -> {
          jdbc.execute("SET LOCAL search_path TO " + schema);
          assertThat(
                  jdbc.queryForObject(
                      "SELECT d.body->>'title' FROM care_item i JOIN content_document d ON d.id=i.current_document_id WHERE i.id=?",
                      String.class,
                      item))
              .isEqualTo("기존 제목");
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM care_item_document_parent WHERE document_id=(SELECT output_document_id FROM ai_proposal WHERE id=?)",
                      Long.class,
                      proposal))
              .isEqualTo(1);
          assertThat(
                  jdbc.queryForObject(
                      "SELECT analysis_inputs_valid(?)",
                      Boolean.class,
                      source.analysis().analysisId()))
              .isTrue();
          jdbc.update("UPDATE ai_proposal SET resolution='APPLIED' WHERE id=?", proposal);
          jdbc.update("UPDATE care_item SET version=version+1 WHERE id=?", item);
          assertThat(
                  jdbc.queryForObject(
                      "SELECT analysis_provenance_valid(?)",
                      Boolean.class,
                      source.analysis().analysisId()))
              .isTrue();
          assertThat(
                  jdbc.queryForObject(
                      "SELECT analysis_inputs_valid(?)",
                      Boolean.class,
                      source.analysis().analysisId()))
              .isFalse();
        });
  }

  @Test
  void memberRemovalReleasesLeaseInTheSameTransaction() {
    UUID item = seedItem("TASK");
    var old = locks.acquire(member, space, item, 1);
    String version =
        jdbc.queryForObject("SELECT version::text FROM care_space WHERE id=?", String.class, space);
    membershipChanges.remove(
        owner, space, memberMembership, new MembershipChangeRequest(version, UUID.randomUUID()));
    assertThat(count("care_item_edit_lock", "item_id", item)).isZero();
    failure(() -> locks.renew(member, space, item, old.leaseToken()), "FORBIDDEN");
    assertThat(locks.acquire(owner, space, item, 1).leaseToken()).isNotBlank();
  }

  private record Spec(
      String operation,
      String type,
      UUID target,
      Map<String, String> changes,
      List<UUID> candidates) {}
}
