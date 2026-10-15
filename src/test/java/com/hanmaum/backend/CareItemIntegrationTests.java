package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hanmaum.backend.ai.dto.*;
import com.hanmaum.backend.analysis.entity.AnalysisStatus;
import com.hanmaum.backend.analysis.service.AnalysisExecutionService;
import com.hanmaum.backend.analysis.service.AnalysisQueryService;
import com.hanmaum.backend.careitem.dto.*;
import com.hanmaum.backend.careitem.repository.CareItemRepository;
import com.hanmaum.backend.careitem.service.*;
import com.hanmaum.backend.carespace.dto.*;
import com.hanmaum.backend.carespace.service.CareSpaceCreationService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.security.oauth.MemberOAuth2User;
import com.hanmaum.backend.record.dto.CreateSourceRecordRequest;
import com.hanmaum.backend.record.service.SourceRecordCreationService;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=4")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CareItemIntegrationTests {
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired CareSpaceCreationService spaces;
  @Autowired CareItemCreationService creation;
  @Autowired CareItemQueryService queries;
  @Autowired SourceRecordCreationService records;
  @Autowired AnalysisExecutionService execution;
  @Autowired AnalysisQueryService analyses;
  @MockitoSpyBean CareItemRepository repository;
  UUID owner, member, membership;
  CreateCareSpaceResponse space;

  @BeforeEach
  void setup() {
    reset(AopTestUtils.<CareItemRepository>getUltimateTargetObject(repository));
    owner = user();
    member = user();
    space = spaces.create(owner, new CreateCareSpaceRequest("일정", UUID.randomUUID()));
    membership = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO space_membership (id, space_id, user_id, joined_at) VALUES (?, ?, ?, now())",
        membership,
        space.spaceId(),
        member);
  }

  @Test
  void storesExactInputBasisHistoryAndReceiptAtomicallyAndReturnsPublicContract() throws Exception {
    var before =
        jdbc.queryForMap("SELECT version, updated_at FROM care_space WHERE id=?", space.spaceId());
    var request = request("　재진 😀  ", new ScheduleFields("2026-10-15", "10:00", " 내과 "));
    var response =
        mvc.perform(
                post(path())
                    .with(authentication(login(owner)))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.version").value("1"))
            .andReturn()
            .getResponse();
    var body = mapper.readTree(response.getContentAsString());
    assertThat(body.size()).isEqualTo(6);
    assertThat(body.path("data").size()).isEqualTo(4);
    UUID item = UUID.fromString(body.at("/data/itemId").asText());
    assertThat(response.getHeader("Location")).isEqualTo(path() + "/" + item);
    var detail = queries.detail(member, space.spaceId(), item);
    assertThat(detail.title()).isEqualTo(request.title());
    assertThat(detail.fields().path("scheduledTime").asText()).isEqualTo("10:00");
    assertThat(detail.fields().path("location").asText()).isEqualTo(" 내과 ");
    assertThat(detail.status()).isEqualTo("SCHEDULED");
    assertThat(detail.createdAt()).isEqualTo(detail.updatedAt());
    assertThat(detail.createdAt().getNano() % 1000).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM care_item_basis WHERE item_id=?", Long.class, item))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM care_item_history WHERE item_id=? AND version=1 AND operation='CREATE'",
                Long.class,
                item))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForMap(
                "SELECT version, updated_at FROM care_space WHERE id=?", space.spaceId()))
        .isEqualTo(before);
    String receipt =
        jdbc.queryForObject(
            "SELECT result_refs::text FROM command_receipt WHERE actor_user_id=? AND client_request_id=?",
            String.class,
            owner,
            request.clientRequestId());
    assertThat(receipt).contains(item.toString()).doesNotContain("재진", "내과", "title", "fields");
    mvc.perform(get(path() + "/" + item).with(authentication(login(member))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.editLock").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.data.answers").isEmpty())
        .andExpect(jsonPath("$.data.userId").doesNotExist());
  }

  @Test
  void nullableDateTimeRemainUnknownAndOmissionReplaysAsExplicitNull() throws Exception {
    UUID key = UUID.randomUUID();
    String body =
        "{\"itemType\":\"SCHEDULE\",\"title\":\"날짜 미정\",\"fields\":{},\"clientRequestId\":\""
            + key
            + "\"}";
    var first =
        mvc.perform(
                post(path())
                    .with(authentication(login(owner)))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    UUID item =
        UUID.fromString(mapper.readTree(first.getContentAsString()).at("/data/itemId").asText());
    var replay =
        creation.create(
            owner,
            space.spaceId(),
            new CreateCareItemRequest(
                "SCHEDULE", "날짜 미정", null, new ScheduleFields(null, null, null), key));
    assertThat(replay.itemId()).isEqualTo(item);
    var fields = queries.detail(owner, space.spaceId(), item).fields();
    assertThat(fields.path("scheduledDate").isNull()).isTrue();
    assertThat(fields.path("scheduledTime").isNull()).isTrue();
    assertThat(count()).isEqualTo(1);
  }

  static Stream<String> invalidRequests() {
    return Stream.of(
        "{\"itemType\":\"TASK\",\"title\":\"업무\",\"fields\":{}}",
        "{\"itemType\":\"SCHEDULE\",\"title\":\"　 \t\",\"fields\":{}}".replace("\t", "\\t"),
        "{\"itemType\":\"SCHEDULE\",\"title\":22,\"fields\":{}}",
        "{\"itemType\":\"SCHEDULE\",\"title\":\"일정\",\"fields\":null}",
        "{\"itemType\":\"SCHEDULE\",\"title\":\"일정\",\"fields\":{\"scheduledDate\":\"2026-02-30\"}}",
        "{\"itemType\":\"SCHEDULE\",\"title\":\"일정\",\"fields\":{\"scheduledDate\":\"2026-1-2\"}}",
        "{\"itemType\":\"SCHEDULE\",\"title\":\"일정\",\"fields\":{\"scheduledTime\":\"24:00\"}}",
        "{\"itemType\":\"SCHEDULE\",\"title\":\"일정\",\"fields\":{\"location\":false}}",
        "{\"itemType\":\"SCHEDULE\",\"title\":\"일정\",\"fields\":{\"assigneeMembershipId\":null}}",
        "{\"itemType\":\"SCHEDULE\",\"title\":\"일정\",\"status\":\"COMPLETED\",\"fields\":{}}",
        "{\"itemType\":\"SCHEDULE\",\"title\":\"\\u0000\",\"fields\":{}}",
        "{\"itemType\":\"SCHEDULE\",\"title\":\"\\ud800\",\"fields\":{}}");
  }

  @ParameterizedTest
  @MethodSource("invalidRequests")
  void rejectsInvalidJsonAndTextWithoutAnyWrites(String fragment) throws Exception {
    String body =
        fragment.substring(0, fragment.length() - 1)
            + ",\"clientRequestId\":\""
            + UUID.randomUUID()
            + "\"}";
    mvc.perform(
            post(path())
                .with(authentication(login(owner)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    assertThat(count()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM command_receipt WHERE actor_user_id=? AND command_kind='CREATE_CARE_ITEM'",
                Long.class,
                owner))
        .isZero();
  }

  @Test
  void requiresSessionCsrfAndCurrentOwnerForWrites() throws Exception {
    String body = mapper.writeValueAsString(request("등록", new ScheduleFields(null, null, null)));
    mvc.perform(post(path()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post(path())
                .with(authentication(login(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(path())
                .with(authentication(login(member)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    assertThat(count()).isZero();
  }

  @Test
  void filtersUseBusinessStateAndRejectDeferredScheduleView() throws Exception {
    var a = create("미정");
    var b = create("지난 날짜");
    jdbc.update("UPDATE care_item SET status='CANCELED' WHERE id=?", b.itemId());
    assertThat(queries.list(member, space.spaceId(), null, null, null).items()).hasSize(2);
    assertThat(queries.list(member, space.spaceId(), "SCHEDULE", "SCHEDULED", null).items())
        .extracting(CareItemSummary::itemId)
        .containsExactly(a.itemId());
    assertThat(queries.list(member, space.spaceId(), "TASK", null, null).items()).isEmpty();
    for (String query :
        List.of(
            "?itemType=bad",
            "?status=COMPLETED",
            "?itemType=SCHEDULE&status=IN_PROGRESS",
            "?scheduleView=PAST"))
      mvc.perform(get(path() + query).with(authentication(login(member))))
          .andExpect(status().isBadRequest());
    assertThat(queries.detail(owner, space.spaceId(), a.itemId()).status()).isEqualTo("SCHEDULED");
  }

  @Test
  void emptyListsAndCrossSpaceIdsStillEnforceCurrentAccess() throws Exception {
    mvc.perform(get(path()).with(authentication(login(member))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items").isEmpty());
    UUID outsider = user();
    mvc.perform(get(path()).with(authentication(login(outsider))))
        .andExpect(status().isForbidden());
    var item = create("정보");
    var other = spaces.create(owner, new CreateCareSpaceRequest("다른 공간", UUID.randomUUID()));
    assertCode(
        () -> queries.detail(owner, other.spaceId(), item.itemId()),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    jdbc.update(
        "UPDATE space_membership SET ended_at=now(),end_reason='LEFT' WHERE id=?", membership);
    assertCode(
        () -> queries.detail(member, space.spaceId(), item.itemId()), CommonErrorCode.FORBIDDEN);
    jdbc.update("UPDATE care_space SET lifecycle='DELETING' WHERE id=?", space.spaceId());
    assertCode(
        () -> queries.list(owner, space.spaceId(), null, null, null),
        CommonErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void repeatedRequestReturnsOriginalMetadataAndRechecksOwnership() {
    var request = request("일정", new ScheduleFields(null, null, null));
    var first = creation.create(owner, space.spaceId(), request);
    assertThat(creation.create(owner, space.spaceId(), request)).isEqualTo(first);
    assertThat(count()).isEqualTo(1);
    assertCode(
        () ->
            creation.create(
                owner,
                space.spaceId(),
                new CreateCareItemRequest(
                    "SCHEDULE", "수정", null, request.fields(), request.clientRequestId())),
        CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    jdbc.update(
        "UPDATE care_space SET owner_membership_id=?,owner_user_id=? WHERE id=?",
        membership,
        member,
        space.spaceId());
    assertCode(() -> creation.create(owner, space.spaceId(), request), CommonErrorCode.FORBIDDEN);
    assertThat(count()).isEqualTo(1);
  }

  @Test
  void erasedDocumentAndDeletedItemCannotBeReplayedOrLeakTheirContent() {
    var request =
        new CreateCareItemRequest(
            "SCHEDULE",
            "민감한 일정",
            "민감한 설명",
            new ScheduleFields("2026-10-15", "10:00", "병원"),
            UUID.randomUUID());
    var item = creation.create(owner, space.spaceId(), request);
    redact(item.itemId());
    var detail = queries.detail(member, space.spaceId(), item.itemId());
    assertThat(detail.title()).isNull();
    assertThat(detail.description()).isNull();
    assertThat(detail.fields().isEmpty()).isTrue();
    assertThat(detail.needsReconfirmation()).isTrue();
    assertThat(detail.redactedFields()).contains("title", "description", "fields");
    assertCode(
        () -> creation.create(owner, space.spaceId(), request), CommonErrorCode.RESOURCE_NOT_FOUND);
    jdbc.update("UPDATE care_item SET visibility='DELETED' WHERE id=?", item.itemId());
    assertThat(queries.list(member, space.spaceId(), null, null, null).items()).isEmpty();
    assertThat(queries.detail(member, space.spaceId(), item.itemId()).status()).isNull();
  }

  @Test
  void deletedItemHidesContentEvenWhenItsDocumentHasNotBeenRedacted() throws Exception {
    var request =
        new CreateCareItemRequest(
            "SCHEDULE",
            "삭제할 일정",
            "남아 있는 설명",
            new ScheduleFields("2026-10-15", "10:00", "병원"),
            UUID.randomUUID());
    var item = creation.create(owner, space.spaceId(), request);
    jdbc.update("UPDATE care_item SET visibility='DELETED' WHERE id=?", item.itemId());
    assertThat(
            jdbc.queryForObject(
                "SELECT d.body::text FROM content_document d JOIN care_item i ON i.current_document_id=d.id WHERE i.id=?",
                String.class,
                item.itemId()))
        .contains("삭제할 일정", "남아 있는 설명", "병원");

    var detail = queries.detail(member, space.spaceId(), item.itemId());
    assertThat(detail.visibility()).isEqualTo("DELETED");
    assertThat(detail.title()).isNull();
    assertThat(detail.description()).isNull();
    assertThat(detail.fields().isEmpty()).isTrue();
    assertThat(detail.status()).isNull();
    assertThat(queries.list(member, space.spaceId(), null, null, null).items()).isEmpty();
    assertCode(
        () -> creation.create(owner, space.spaceId(), request), CommonErrorCode.RESOURCE_NOT_FOUND);
    var response =
        mvc.perform(get(path() + "/" + item.itemId()).with(authentication(login(member))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain("삭제할 일정", "남아 있는 설명", "병원");
  }

  @Test
  void rollsBackDocumentBasisHistoryAndReceiptWhenPersistenceFails() {
    doAnswer(
            call -> {
              call.callRealMethod();
              throw new DataAccessResourceFailureException("test failure");
            })
        .when(AopTestUtils.<CareItemRepository>getUltimateTargetObject(repository))
        .insert(any(), any(), any(), any(), any(), anyString(), any());
    var request = request("실패", new ScheduleFields(null, null, null));
    assertThatThrownBy(() -> creation.create(owner, space.spaceId(), request))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(count()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content_document WHERE space_id=?",
                Long.class,
                space.spaceId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM command_receipt WHERE actor_user_id=? AND client_request_id=?",
                Long.class,
                owner,
                request.clientRequestId()))
        .isZero();
    reset(AopTestUtils.<CareItemRepository>getUltimateTargetObject(repository));
    assertThat(creation.create(owner, space.spaceId(), request).itemId()).isNotNull();
  }

  @Test
  void sameKeyInFlightDoesNotExecuteTwice() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    doAnswer(
            call -> {
              entered.countDown();
              if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
              return call.callRealMethod();
            })
        .when(AopTestUtils.<CareItemRepository>getUltimateTargetObject(repository))
        .insert(any(), any(), any(), any(), any(), anyString(), any());
    var request = request("한 번", new ScheduleFields(null, null, null));
    try (var pool = Executors.newSingleThreadExecutor()) {
      var future = pool.submit(() -> creation.create(owner, space.spaceId(), request));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertCode(
            () -> creation.create(owner, space.spaceId(), request),
            CommonErrorCode.REQUEST_IN_PROGRESS);
      } finally {
        release.countDown();
      }
      var first = future.get(10, TimeUnit.SECONDS);
      assertThat(creation.create(owner, space.spaceId(), request)).isEqualTo(first);
      assertThat(count()).isEqualTo(1);
    }
  }

  @Test
  void analysisUsesOnlyValidSameSpaceCandidatesAndRevalidatesTheirDocuments() {
    var candidate = create("기존 일정");
    var other = spaces.create(owner, new CreateCareSpaceRequest("다른", UUID.randomUUID()));
    creation.create(owner, other.spaceId(), request("다른 공간", new ScheduleFields(null, null, null)));
    var source =
        records.create(
            owner, space.spaceId(), new CreateSourceRecordRequest("새 소식", UUID.randomUUID()));
    var work =
        execution
            .claim(space.spaceId(), source.analysis().analysisId(), Duration.ofMinutes(2))
            .orElseThrow();
    assertThat(work.request().candidates())
        .extracting(AiAnalysisRequest.CandidateItem::itemId)
        .containsExactly(candidate.itemId());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analysis_input_item WHERE run_id=?",
                Long.class,
                work.run().id()))
        .isEqualTo(1);
    redact(candidate.itemId());
    assertThat(
            execution.complete(work, new AiAnalysisResponse(work.request().requestId(), List.of())))
        .isFalse();
    assertThat(analyses.find(owner, space.spaceId(), source.recordId(), work.run().id()).status())
        .isEqualTo(AnalysisStatus.DISCARDED);
  }

  @Test
  void candidateSourceDependenciesDiscardLateResultsAfterOriginalErasure() {
    var candidate = create("근거 연결된 일정");
    var basis =
        records.create(
            owner, space.spaceId(), new CreateSourceRecordRequest("근거 소식", UUID.randomUUID()));
    UUID evidence = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO evidence (id,space_id,record_id,record_version,quote,created_at) VALUES (?,?,?,1,'근거 소식',now())",
        evidence,
        space.spaceId(),
        basis.recordId());
    jdbc.update(
        "INSERT INTO care_item_basis (id,space_id,item_id,source_evidence_id,created_at) VALUES (?,?,?,?,now())",
        UUID.randomUUID(),
        space.spaceId(),
        candidate.itemId(),
        evidence);
    var source =
        records.create(
            owner, space.spaceId(), new CreateSourceRecordRequest("새 소식", UUID.randomUUID()));
    var work =
        execution
            .claim(space.spaceId(), source.analysis().analysisId(), Duration.ofMinutes(2))
            .orElseThrow();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM analysis_input_source WHERE run_id=?",
                Long.class,
                work.run().id()))
        .isEqualTo(2);
    jdbc.update(
        "UPDATE source_record SET lifecycle='ERASED',body=NULL,erased_at=now(),version=version+1 WHERE id=?",
        basis.recordId());
    assertThat(
            execution.complete(work, new AiAnalysisResponse(work.request().requestId(), List.of())))
        .isFalse();
    assertThat(analyses.find(owner, space.spaceId(), source.recordId(), work.run().id()).status())
        .isEqualTo(AnalysisStatus.DISCARDED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNCHANGED", "REDACTED", "REMOVED", "LEGACY"})
  void completionRevalidatesCapturedEvidenceWithoutRelyingOnSourceOrItemChanges(String change) {
    var candidate = create("근거 연결 일정");
    var basis =
        records.create(
            owner, space.spaceId(), new CreateSourceRecordRequest("근거 소식", UUID.randomUUID()));
    UUID evidence = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO evidence (id,space_id,record_id,record_version,quote,created_at) VALUES (?,?,?,1,'근거 소식',now())",
        evidence,
        space.spaceId(),
        basis.recordId());
    jdbc.update(
        "INSERT INTO care_item_basis (id,space_id,item_id,source_evidence_id,created_at) VALUES (?,?,?,?,now())",
        UUID.randomUUID(),
        space.spaceId(),
        candidate.itemId(),
        evidence);
    var source =
        records.create(
            owner, space.spaceId(), new CreateSourceRecordRequest("새 소식", UUID.randomUUID()));
    var work =
        execution
            .claim(space.spaceId(), source.analysis().analysisId(), Duration.ofMinutes(2))
            .orElseThrow();
    assertThat(work.request().candidates())
        .extracting(AiAnalysisRequest.CandidateItem::itemId)
        .containsExactly(candidate.itemId());
    assertThat(
            jdbc.queryForObject(
                "SELECT source_evidence_ids = ARRAY[?]::uuid[] FROM analysis_input_item WHERE run_id=? AND item_id=?",
                Boolean.class,
                evidence,
                work.run().id(),
                candidate.itemId()))
        .isTrue();
    var beforeItem =
        jdbc.queryForMap(
            "SELECT version,current_document_id FROM care_item WHERE id=?", candidate.itemId());
    var beforeSource =
        jdbc.queryForMap(
            "SELECT version,lifecycle,body FROM source_record WHERE id=?", basis.recordId());
    switch (change) {
      case "REDACTED" ->
          jdbc.update("UPDATE evidence SET quote=NULL,redacted_at=now() WHERE id=?", evidence);
      case "REMOVED" -> {
        jdbc.update("DELETE FROM care_item_basis WHERE source_evidence_id=?", evidence);
        jdbc.update("DELETE FROM evidence WHERE id=?", evidence);
      }
      case "LEGACY" ->
          jdbc.update(
              "UPDATE analysis_input_item SET source_evidence_ids=NULL WHERE run_id=?",
              work.run().id());
      default -> {}
    }
    assertThat(
            jdbc.queryForMap(
                "SELECT version,current_document_id FROM care_item WHERE id=?", candidate.itemId()))
        .isEqualTo(beforeItem);
    assertThat(
            jdbc.queryForMap(
                "SELECT version,lifecycle,body FROM source_record WHERE id=?", basis.recordId()))
        .isEqualTo(beforeSource);
    var response =
        new AiAnalysisResponse(
            work.request().requestId(),
            List.of(
                new AiAnalysisResponse.Suggestion(
                    AiAnalysisResponse.Operation.CREATE,
                    CareItemType.OBSERVATION,
                    null,
                    null,
                    List.of(),
                    "분석 결과",
                    "새 소식",
                    Map.of("content", "새 소식"))));
    boolean valid = change.equals("UNCHANGED");
    assertThat(execution.complete(work, response)).isEqualTo(valid);
    var result = analyses.find(owner, space.spaceId(), source.recordId(), work.run().id());
    assertThat(result.status())
        .isEqualTo(valid ? AnalysisStatus.COMPLETED : AnalysisStatus.DISCARDED);
    assertThat(result.proposalIds()).hasSize(valid ? 1 : 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ai_proposal WHERE run_id=?", Long.class, work.run().id()))
        .isEqualTo(valid ? 1L : 0L);
    assertThat(count()).isEqualTo(1);
  }

  @Test
  void directInputCandidateCapturesEmptyEvidenceAndCompletesNormally() {
    var candidate = create("직접 등록 일정");
    var source =
        records.create(
            owner, space.spaceId(), new CreateSourceRecordRequest("새 소식", UUID.randomUUID()));
    var work =
        execution
            .claim(space.spaceId(), source.analysis().analysisId(), Duration.ofMinutes(2))
            .orElseThrow();
    assertThat(
            jdbc.queryForObject(
                "SELECT source_evidence_ids = '{}'::uuid[] FROM analysis_input_item WHERE run_id=? AND item_id=?",
                Boolean.class,
                work.run().id(),
                candidate.itemId()))
        .isTrue();
    assertThat(
            execution.complete(work, new AiAnalysisResponse(work.request().requestId(), List.of())))
        .isTrue();
    assertThat(analyses.find(owner, space.spaceId(), source.recordId(), work.run().id()).status())
        .isEqualTo(AnalysisStatus.COMPLETED);
  }

  @Test
  void longPublicInputIsStoredButInternalCandidateTransportFailsClosedWithoutTruncation() {
    create("긴".repeat(2100));
    var source =
        records.create(
            owner, space.spaceId(), new CreateSourceRecordRequest("소식", UUID.randomUUID()));
    assertThat(
            execution.claim(space.spaceId(), source.analysis().analysisId(), Duration.ofMinutes(2)))
        .isEmpty();
    assertThat(
            analyses
                .find(owner, space.spaceId(), source.recordId(), source.analysis().analysisId())
                .failure()
                .code())
        .isEqualTo("ANALYSIS_DEPENDENCY_UNAVAILABLE");
  }

  @Test
  void swaggerShowsSupportedApiAndExplicitSessionAndCsrf() throws Exception {
    var json =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var doc = mapper.readTree(json);
    var security = doc.at("/paths/~1api~1spaces~1{spaceId}~1items/post/security");
    assertThat(security.isArray()).isTrue();
    assertThat(security.size()).isEqualTo(1);
    assertThat(security.get(0).size()).isEqualTo(2);
    assertThat(security.at("/0/SessionCookie").isArray()).isTrue();
    assertThat(security.at("/0/CsrfToken").isArray()).isTrue();
    for (String field : List.of("title", "status", "assigneeDisplayName")) {
      var types = doc.at("/components/schemas/CareItemSummary/properties/" + field + "/type");
      assertThat(types.isArray()).as(field).isTrue();
      assertThat(types.size()).as(field).isEqualTo(2);
      assertThat(List.of(types.get(0).asText(), types.get(1).asText()))
          .as(field)
          .containsExactlyInAnyOrder("string", "null");
    }
    assertThat(
            doc.at("/components/schemas/CreateCareItemRequest/properties/itemType/enum").toString())
        .contains("SCHEDULE")
        .doesNotContain("TASK");
    assertThat(
            doc.at("/paths/~1api~1spaces~1{spaceId}~1items~1{itemId}/get/responses/200")
                .isMissingNode())
        .isFalse();
    assertThat(json).contains("CREATE_CARE_ITEM_CONFLICT");
  }

  private CreateCareItemRequest request(String title, ScheduleFields fields) {
    return new CreateCareItemRequest("SCHEDULE", title, null, fields, UUID.randomUUID());
  }

  private CreateCareItemResponse create(String title) {
    return creation.create(
        owner, space.spaceId(), request(title, new ScheduleFields(null, null, null)));
  }

  private String path() {
    return "/api/spaces/" + space.spaceId() + "/items";
  }

  private long count() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM care_item WHERE space_id=?", Long.class, space.spaceId());
  }

  private void redact(UUID item) {
    jdbc.update(
        "UPDATE content_document SET body=NULL,redacted_at=now() WHERE id=(SELECT current_document_id FROM care_item WHERE id=?)",
        item);
  }

  private UUID user() {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO app_user (id,created_at,updated_at) VALUES (?,now(),now())", id);
    jdbc.update(
        "INSERT INTO social_account (id,user_id,provider,provider_user_id,created_at) VALUES (?,?,'KAKAO',?,now())",
        UUID.randomUUID(),
        id,
        id.toString());
    return id;
  }

  private OAuth2AuthenticationToken login(UUID user) {
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    var principal = new DefaultOAuth2User(authorities, Map.of("id", user.toString()), "id");
    return new OAuth2AuthenticationToken(
        new MemberOAuth2User(user, principal), authorities, "kakao");
  }

  private static void assertCode(Runnable action, CommonErrorCode code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
  }
}
