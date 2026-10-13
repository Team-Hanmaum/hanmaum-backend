package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hanmaum.backend.ai.client.*;
import com.hanmaum.backend.ai.dto.*;
import com.hanmaum.backend.analysis.code.*;
import com.hanmaum.backend.analysis.dto.*;
import com.hanmaum.backend.analysis.entity.AnalysisStatus;
import com.hanmaum.backend.analysis.repository.AnalysisRepository;
import com.hanmaum.backend.analysis.service.*;
import com.hanmaum.backend.carespace.dto.*;
import com.hanmaum.backend.carespace.service.CareSpaceCreationService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.code.ErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.security.oauth.MemberOAuth2User;
import com.hanmaum.backend.proposal.repository.AnalysisProposalRepository;
import com.hanmaum.backend.record.dto.*;
import com.hanmaum.backend.record.service.*;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=4")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AnalysisIntegrationTests {
  // Test-only execution timings, not application/operational defaults.
  private static final Duration TEST_LEASE = Duration.ofMinutes(2);
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired CareSpaceCreationService spaces;
  @Autowired SourceRecordCreationService records;
  @Autowired SourceRecordQueryService recordQueries;
  @Autowired AnalysisSubmissionService submissions;
  @Autowired AnalysisQueryService queries;
  @Autowired AnalysisExecutionService execution;
  @Autowired AnalysisProcessor processor;
  @Autowired PlatformTransactionManager transactions;
  @MockitoSpyBean AnalysisRepository runs;
  @MockitoSpyBean AnalysisProposalRepository proposals;
  @MockitoBean HanmaumAiClient client;
  UUID owner, member, outsider, membership;
  CreateCareSpaceResponse space;

  @BeforeEach
  void setup() {
    owner = user();
    member = user();
    outsider = user();
    space = spaces.create(owner, new CreateCareSpaceRequest("분석 테스트", UUID.randomUUID()));
    membership = join(member);
    when(client.analyze(any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              AiAnalysisRequest request = call.getArgument(0);
              return new AiAnalysisResponse(request.requestId(), List.of());
            });
  }

  @Test
  void savingCreatesOneDurableQueueAndReplaysOriginalAcceptanceAfterCompletion() {
    var request = new CreateSourceRecordRequest("다음 달 병원", UUID.randomUUID());
    var created = records.create(member, space.spaceId(), request);
    assertThat(created.analysis().status()).isEqualTo(AnalysisStatus.QUEUED);
    var original = detail(created);
    assertThat(original.startedAt()).isNull();
    assertThat(original.finishedAt()).isNull();
    assertThat(original.canRetry()).isFalse();
    processor.process(claim(created));
    assertThat(detail(created).status()).isEqualTo(AnalysisStatus.COMPLETED);
    assertThat(detail(created).proposalIds()).isEmpty();
    assertThat(records.create(member, space.spaceId(), request)).isEqualTo(created);
    assertThat(count("analysis_run", created)).isEqualTo(1);
    assertThat(
            recordQueries
                .detail(member, space.spaceId(), created.recordId())
                .latestAnalysis()
                .status())
        .isEqualTo(AnalysisStatus.COMPLETED);
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM source_record WHERE id = ?", Long.class, created.recordId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM care_space WHERE id = ?", Long.class, space.spaceId()))
        .isEqualTo(1);
  }

  @Test
  void enqueueFailureRollsBackOriginalAndReceipt() {
    var request = new CreateSourceRecordRequest("원본", UUID.randomUUID());
    doThrow(new DataAccessResourceFailureException("synthetic failure"))
        .when(runs)
        .enqueue(any(), any(), any(), any(), any(), any());
    assertThatThrownBy(() -> records.create(member, space.spaceId(), request))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM source_record WHERE space_id = ?",
                Integer.class,
                space.spaceId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM command_receipt WHERE client_request_id = ?",
                Integer.class,
                request.clientRequestId()))
        .isZero();
  }

  @Test
  void successfulProposalsAndEvidenceAreStoredAtomicallyWithoutCreatingItems() {
    var created = create();
    var work = claim(created);
    doReturn(response(work, 2)).when(client).analyze(any());
    processor.process(work);
    var result = detail(created);
    assertThat(result.status()).isEqualTo(AnalysisStatus.COMPLETED);
    assertThat(result.proposalIds()).hasSize(2).doesNotHaveDuplicates();
    assertThat(recordQueries.detail(owner, space.spaceId(), created.recordId()).proposalIds())
        .isEqualTo(result.proposalIds());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM evidence WHERE record_id = ? AND quote = '원본 소식'",
                Integer.class,
                created.recordId()))
        .isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT to_regclass('care_item') IS NULL", Boolean.class))
        .isTrue();
    assertThat(execution.complete(work, response(work, 2))).isFalse();
    assertThat(detail(created).proposalIds()).hasSize(2);
  }

  @Test
  void partialProposalFailureLeavesNoDocumentsEvidenceOrProposals() {
    var created = create();
    var work = claim(created);
    doReturn(response(work, 2)).when(client).analyze(any());
    doThrow(new DataAccessResourceFailureException("synthetic secret"))
        .when((AnalysisProposalRepository) AopTestUtils.getUltimateTargetObject(proposals))
        .insert(any(), eq(1), any());
    processor.process(work);
    assertThat(detail(created).failure().code()).isEqualTo("ANALYSIS_STORAGE_FAILED");
    assertThat(detail(created).proposalIds()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM evidence WHERE record_id = ?",
                Integer.class,
                created.recordId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content_document WHERE space_id = ?",
                Integer.class,
                space.spaceId()))
        .isZero();
    assertThat(detail(created).canRetry()).isTrue();
  }

  @Test
  void upstreamFailurePreservesOriginalAndIsA200SuccessfulStatusRead() throws Exception {
    var created = create();
    doThrow(new AiServiceException()).when(client).analyze(any());
    processor.process(claim(created));
    mvc.perform(
            get(path(created) + "/" + created.analysis().analysisId())
                .with(authentication(login(owner))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.status").value("FAILED"))
        .andExpect(jsonPath("$.data.failure.code").value("AI_SERVICE_UNAVAILABLE"))
        .andExpect(jsonPath("$.data.canRetry").value(true));
    assertThat(recordQueries.detail(member, space.spaceId(), created.recordId()).content())
        .isEqualTo("원본 소식");
  }

  @Test
  void retryCreatesANewAttemptWithFixedDateAndReplaysEvenAfterItFinishes() {
    var created = create();
    var firstWork = claim(created);
    execution.fail(firstWork, AnalysisFailureCode.AI_SERVICE_UNAVAILABLE);
    var request = retryRequest(created);
    var accepted = submissions.retry(owner, space.spaceId(), created.recordId(), request);
    assertThat(accepted.analysisId()).isNotEqualTo(created.analysis().analysisId());
    var next = execution.claim(space.spaceId(), accepted.analysisId(), TEST_LEASE).orElseThrow();
    assertThat(next.request().recordedAt()).isEqualTo(firstWork.request().recordedAt());
    assertThat(next.request().timezone()).isEqualTo("Asia/Seoul");
    assertThat(next.request().requestId()).isNotEqualTo(firstWork.request().requestId());
    processor.process(next);
    assertThat(submissions.retry(owner, space.spaceId(), created.recordId(), request))
        .isEqualTo(accepted);
    assertThat(detail(created).canRetry()).isFalse();
    assertCode(
        () -> submissions.retry(owner, space.spaceId(), created.recordId(), retryRequest(created)),
        AnalysisErrorCode.ANALYSIS_STATE_CONFLICT);
    assertThat(count("analysis_run", created)).isEqualTo(2);
  }

  @Test
  void retryValidatesVersionScopeAndIdempotencyWithoutLeakingAnotherSpace() {
    var created = failed();
    assertCode(
        () ->
            submissions.retry(
                owner,
                space.spaceId(),
                created.recordId(),
                new RetryAnalysisRequest(created.analysis().analysisId(), "2", UUID.randomUUID())),
        CommonErrorCode.VERSION_CONFLICT);
    var request = retryRequest(created);
    submissions.retry(owner, space.spaceId(), created.recordId(), request);
    assertCode(
        () ->
            submissions.retry(
                owner,
                space.spaceId(),
                created.recordId(),
                new RetryAnalysisRequest(
                    created.analysis().analysisId(), "2", request.clientRequestId())),
        CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    assertCode(
        () ->
            submissions.retry(outsider, space.spaceId(), created.recordId(), retryRequest(created)),
        CommonErrorCode.FORBIDDEN);
    assertCode(
        () -> queries.find(owner, space.spaceId(), created.recordId(), UUID.randomUUID()),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    var other = create();
    assertCode(
        () ->
            queries.find(owner, space.spaceId(), other.recordId(), created.analysis().analysisId()),
        CommonErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void authenticationCsrfValidationAndSwaggerMatchTheContract() throws Exception {
    var created = failed();
    var request = retryRequest(created);
    String body = mapper.writeValueAsString(request);
    mvc.perform(get(path(created) + "/" + created.analysis().analysisId()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post(path(created))
                .with(authentication(login(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(path(created))
                .with(authentication(login(owner)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body.replace("\"expectedRecordVersion\":\"1\"", "\"expectedRecordVersion\":1")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post(path(created))
                .with(authentication(login(owner)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.code").value("ACCEPTED"))
        .andExpect(jsonPath("$.data.status").value("QUEUED"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().exists("Location"));
    var doc =
        mapper.readTree(
            mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
    var operation =
        doc.at("/paths/~1api~1spaces~1{spaceId}~1source-records~1{recordId}~1analyses/post");
    assertThat(operation.at("/security/0/SessionCookie").isMissingNode()).isFalse();
    assertThat(operation.at("/security/0/CsrfToken").isMissingNode()).isFalse();
    assertThat(operation.path("parameters").toString()).doesNotContain("userId");
    assertThat(operation.at("/responses/202/content/application~1json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/ApiResponseAnalysisAccepted");
    var queryOperation =
        doc.at(
            "/paths/~1api~1spaces~1{spaceId}~1source-records~1{recordId}~1analyses~1{analysisId}/get");
    assertThat(queryOperation.at("/responses/200/content/application~1json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/ApiResponseAnalysisDetail");
    assertThat(
            doc.at(
                    "/components/responses/RETRY_ANALYSIS_CONFLICT/content/application~1json/examples")
                .size())
        .isEqualTo(4);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "01", "9223372036854775808", "x"})
  void invalidVersionsReturn400(String version) {
    var created = failed();
    assertCode(
        () ->
            submissions.retry(
                owner,
                space.spaceId(),
                created.recordId(),
                new RetryAnalysisRequest(
                    created.analysis().analysisId(), version, UUID.randomUUID())),
        CommonErrorCode.INVALID_REQUEST);
  }

  @Test
  void endedRequesterFailsBeforeCallAndCurrentMemberCanRetry() {
    var created = create();
    end();
    assertThat(execution.claim(space.spaceId(), created.analysis().analysisId(), TEST_LEASE))
        .isEmpty();
    assertThat(detail(created).failure().code()).isEqualTo("ANALYSIS_REQUESTER_INACTIVE");
    verifyNoInteractions(client);
    assertCode(
        () ->
            queries.find(
                member, space.spaceId(), created.recordId(), created.analysis().analysisId()),
        CommonErrorCode.FORBIDDEN);
    var retry =
        submissions.retry(owner, space.spaceId(), created.recordId(), retryRequest(created));
    assertThat(execution.claim(space.spaceId(), retry.analysisId(), TEST_LEASE)).isPresent();
  }

  @Test
  void requesterDepartureDuringCallRejectsResultAndRejoiningDoesNotRestoreOldReceipt() {
    var created = failed();
    var request = retryRequest(created);
    var retry = submissions.retry(member, space.spaceId(), created.recordId(), request);
    var work = execution.claim(space.spaceId(), retry.analysisId(), TEST_LEASE).orElseThrow();
    end();
    assertThat(execution.complete(work, response(work, 1))).isFalse();
    assertThat(
            queries
                .find(owner, space.spaceId(), created.recordId(), retry.analysisId())
                .failure()
                .code())
        .isEqualTo("ANALYSIS_REQUESTER_INACTIVE");
    join(member);
    assertCode(
        () -> submissions.retry(member, space.spaceId(), created.recordId(), request),
        CommonErrorCode.FORBIDDEN);
  }

  @Test
  void expiredExecutionIsFailedWithoutRedeliveryAndItsLateResultIsRejected() {
    var created = create();
    var old = claim(created);
    expire(created.analysis().analysisId());
    assertThat(runs.heartbeat(old.run().id(), old.run().workerToken(), TEST_LEASE)).isFalse();
    execution.recover(space.spaceId(), old.run().id());
    assertThat(detail(created).failure().code()).isEqualTo("ANALYSIS_INTERRUPTED");
    var retry =
        submissions.retry(owner, space.spaceId(), created.recordId(), retryRequest(created));
    var next = execution.claim(space.spaceId(), retry.analysisId(), TEST_LEASE).orElseThrow();
    assertThat(execution.complete(old, response(old, 1))).isFalse();
    processor.process(next);
    assertThat(
            queries.find(owner, space.spaceId(), created.recordId(), retry.analysisId()).status())
        .isEqualTo(AnalysisStatus.COMPLETED);
    verify(client, times(1)).analyze(any());
  }

  @Test
  void recoveryDoesNotTouchLiveExecutionAndHeartbeatCannotReviveFinishedWork() {
    var created = create();
    var work = claim(created);
    execution.recover(space.spaceId(), work.run().id());
    assertThat(detail(created).status()).isEqualTo(AnalysisStatus.RUNNING);
    assertThat(runs.heartbeat(work.run().id(), UUID.randomUUID(), TEST_LEASE)).isFalse();
    assertThat(runs.heartbeat(work.run().id(), work.run().workerToken(), TEST_LEASE)).isTrue();
    processor.process(work);
    assertThat(runs.heartbeat(work.run().id(), work.run().workerToken(), TEST_LEASE)).isFalse();
  }

  @Test
  void originalErasureDuringExecutionDiscardsLateResultAndHidesStatus() {
    var created = create();
    var work = claim(created);
    erase(created, false);
    assertThat(execution.complete(work, response(work, 1))).isFalse();
    assertThat(
            runs.find(space.spaceId(), created.recordId(), work.run().id()).orElseThrow().status())
        .isEqualTo(AnalysisStatus.DISCARDED);
    assertCode(
        () -> queries.find(owner, space.spaceId(), created.recordId(), work.run().id()),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content_document WHERE space_id = ?",
                Integer.class,
                space.spaceId()))
        .isZero();
  }

  @Test
  void completedResultRedactionRemovesCopiedContentAndRollsBackWithOriginalDeletion() {
    var created = create();
    var work = claim(created);
    execution.complete(work, response(work, 1));
    var tx = new TransactionTemplate(transactions);
    tx.executeWithoutResult(
        status -> {
          execution.invalidateSource(space.spaceId(), created.recordId());
          status.setRollbackOnly();
        });
    assertThat(detail(created).status()).isEqualTo(AnalysisStatus.COMPLETED);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content_document WHERE space_id = ? AND body IS NOT NULL",
                Integer.class,
                space.spaceId()))
        .isEqualTo(1);
    erase(created, true);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content_document WHERE space_id = ? AND body IS NOT NULL",
                Integer.class,
                space.spaceId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM evidence WHERE record_id = ? AND quote IS NOT NULL",
                Integer.class,
                created.recordId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ai_proposal WHERE run_id = ? AND invalidated_at IS NULL",
                Integer.class,
                work.run().id()))
        .isZero();
  }

  @Test
  void invalidatingAnotherInputSourceAlsoDiscardsDependentResults() {
    var source = create();
    var dependent = create();
    var work = claim(dependent);
    jdbc.update(
        "INSERT INTO analysis_input_source VALUES (?, ?, ?, 1)",
        space.spaceId(),
        work.run().id(),
        source.recordId());
    execution.complete(work, response(work, 1));
    erase(source, true);
    assertThat(detail(dependent).status()).isEqualTo(AnalysisStatus.DISCARDED);
    assertThat(detail(dependent).proposalIds()).isEmpty();
  }

  @Test
  void versionChangeBeforeCompletionDiscardsResult() {
    var created = create();
    var work = claim(created);
    jdbc.update("UPDATE source_record SET version = 2 WHERE id = ?", created.recordId());
    assertThat(execution.complete(work, response(work, 1))).isFalse();
    assertThat(detail(created).status()).isEqualTo(AnalysisStatus.DISCARDED);
  }

  @Test
  void simultaneousDifferentRetryRequestsCreateOnlyOneAttempt() throws Exception {
    var created = failed();
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = new ArrayList<Future<String>>();
      for (int i = 0; i < 2; i++)
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    submissions.retry(
                        owner, space.spaceId(), created.recordId(), retryRequest(created));
                    return "OK";
                  } catch (ApiException e) {
                    return e.code().code();
                  }
                }));
      gate.countDown();
      var results =
          List.of(
              futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
      assertThat(results).containsExactlyInAnyOrder("OK", "ANALYSIS_STATE_CONFLICT");
    }
    assertThat(count("analysis_run", created)).isEqualTo(2);
  }

  @Test
  void simultaneousWorkersClaimOnlyOnce() throws Exception {
    var created = create();
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> claim =
          () -> {
            gate.await();
            return execution
                .claim(space.spaceId(), created.analysis().analysisId(), TEST_LEASE)
                .isPresent();
          };
      var a = pool.submit(claim);
      var b = pool.submit(claim);
      gate.countDown();
      assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
  }

  @Test
  void workerRequiresExplicitOperatingValues() {
    var ai =
        new AiProperties(
            java.net.URI.create("http://localhost:8000"),
            "test",
            Duration.ofSeconds(3),
            Duration.ofSeconds(30));
    assertThatThrownBy(
            () -> new AnalysisWorkerConfiguration.Settings(true, null, null, null).validate(ai))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AnalysisWorkerConfiguration.Settings(
                        true, 1, Duration.ofSeconds(1), Duration.ofSeconds(30))
                    .validate(ai))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void actualWorkerPicksUpDurableQueuedWorkWithTestOnlyConfiguration() throws Exception {
    var created = create();
    var settings =
        new AnalysisWorkerConfiguration.Settings(true, 1, Duration.ofMillis(20), TEST_LEASE);
    try (var worker = new AnalysisWorker(settings, runs, execution, processor)) {
      worker.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (detail(created).status() != AnalysisStatus.COMPLETED && System.nanoTime() < deadline)
        Thread.sleep(20);
      assertThat(detail(created).status()).isEqualTo(AnalysisStatus.COMPLETED);
    }
  }

  @Test
  void invalidAiResultIsNotPersistedAndDoesNotLeakItsContent() {
    var created = create();
    var work = claim(created);
    doReturn(new AiAnalysisResponse(UUID.randomUUID(), List.of())).when(client).analyze(any());
    processor.process(work);
    assertThat(detail(created).failure().code()).isEqualTo("AI_SERVICE_UNAVAILABLE");
    assertThat(detail(created).proposalIds()).isEmpty();
  }

  @Test
  void missingEvidenceInOriginalRejectsTheWholeResult() {
    var created = create();
    var work = claim(created);
    var invalid =
        new AiAnalysisResponse.Suggestion(
            AiAnalysisResponse.Operation.CREATE,
            CareItemType.OBSERVATION,
            null,
            null,
            List.of(),
            "title",
            "not in original",
            Map.of());
    doReturn(new AiAnalysisResponse(work.request().requestId(), List.of(invalid)))
        .when(client)
        .analyze(any());
    processor.process(work);
    assertThat(detail(created).failure().code()).isEqualTo("AI_SERVICE_UNAVAILABLE");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM content_document WHERE space_id = ?",
                Integer.class,
                space.spaceId()))
        .isZero();
  }

  @Test
  void careItemStorageCannotBeSilentlyIgnoredWhenThatDomainArrives() {
    var created = create();
    jdbc.execute("CREATE TABLE care_item (id uuid PRIMARY KEY)");
    try {
      assertThat(execution.claim(space.spaceId(), created.analysis().analysisId(), TEST_LEASE))
          .isEmpty();
      assertThat(detail(created).failure().code()).isEqualTo("ANALYSIS_DEPENDENCY_UNAVAILABLE");
      verifyNoInteractions(client);
    } finally {
      jdbc.execute("DROP TABLE care_item");
    }
  }

  @Test
  void historicalSourceReplayDoesNotBackfillAnalysis() {
    var request = new CreateSourceRecordRequest("이전 원문", UUID.randomUUID());
    var created = records.create(member, space.spaceId(), request);
    // Simulates an original/receipt persisted before V6, in the isolated test database only.
    jdbc.update(
        "DELETE FROM analysis_input_source WHERE run_id = ?", created.analysis().analysisId());
    jdbc.update("DELETE FROM analysis_run WHERE id = ?", created.analysis().analysisId());
    assertThat(records.create(member, space.spaceId(), request).analysis()).isNull();
    assertThat(recordQueries.detail(member, space.spaceId(), created.recordId()).latestAnalysis())
        .isNull();
    assertThat(count("analysis_run", created)).isZero();
  }

  @Test
  void requestKeyIsSharedWithOtherCommands() {
    var originalRequest = new CreateSourceRecordRequest("원본 소식", UUID.randomUUID());
    var created = records.create(member, space.spaceId(), originalRequest);
    execution.fail(claim(created), AnalysisFailureCode.AI_SERVICE_UNAVAILABLE);
    assertCode(
        () ->
            submissions.retry(
                member,
                space.spaceId(),
                created.recordId(),
                new RetryAnalysisRequest(
                    created.analysis().analysisId(), "1", originalRequest.clientRequestId())),
        CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
  }

  @Test
  void deletingSpaceBeforeDispatchDiscardsQueuedRunWithoutCallingAi() {
    var created = create();
    jdbc.update("UPDATE care_space SET lifecycle = 'DELETING' WHERE id = ?", space.spaceId());
    assertThat(execution.claim(space.spaceId(), created.analysis().analysisId(), TEST_LEASE))
        .isEmpty();
    assertThat(
            runs.find(space.spaceId(), created.recordId(), created.analysis().analysisId())
                .orElseThrow()
                .status())
        .isEqualTo(AnalysisStatus.DISCARDED);
    assertCode(
        () ->
            queries.find(
                owner, space.spaceId(), created.recordId(), created.analysis().analysisId()),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    verifyNoInteractions(client);
  }

  @Test
  void changingOwnerWhileRequesterRemainsActiveDoesNotCancelAnalysis() {
    var created = create();
    var work = claim(created);
    jdbc.update(
        "UPDATE care_space SET owner_user_id = ?, owner_membership_id = ? WHERE id = ?",
        member,
        membership,
        space.spaceId());
    processor.process(work);
    assertThat(detail(created).status()).isEqualTo(AnalysisStatus.COMPLETED);
  }

  @Test
  void staleVersionOrInvalidInputCannotBeSavedThroughRetryReplay() {
    var created = failed();
    var request = retryRequest(created);
    var accepted = submissions.retry(owner, space.spaceId(), created.recordId(), request);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> execution.invalidateSource(space.spaceId(), created.recordId()));
    assertCode(
        () -> submissions.retry(owner, space.spaceId(), created.recordId(), request),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    assertThat(execution.claim(space.spaceId(), accepted.analysisId(), TEST_LEASE)).isEmpty();
  }

  private UUID user() {
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

  private UUID join(UUID user) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO space_membership (id, space_id, user_id, joined_at) VALUES (?, ?, ?, now())",
        id,
        space.spaceId(),
        user);
    return id;
  }

  private void end() {
    jdbc.update(
        "UPDATE space_membership SET ended_at = now(), end_reason = 'LEFT' WHERE id = ?",
        membership);
  }

  private CreateSourceRecordResponse create() {
    return records.create(
        member, space.spaceId(), new CreateSourceRecordRequest("원본 소식", UUID.randomUUID()));
  }

  private AnalysisExecutionService.Work claim(CreateSourceRecordResponse created) {
    return execution
        .claim(space.spaceId(), created.analysis().analysisId(), TEST_LEASE)
        .orElseThrow();
  }

  private AnalysisDetail detail(CreateSourceRecordResponse c) {
    return queries.find(owner, space.spaceId(), c.recordId(), c.analysis().analysisId());
  }

  private CreateSourceRecordResponse failed() {
    var c = create();
    execution.fail(claim(c), AnalysisFailureCode.AI_SERVICE_UNAVAILABLE);
    return c;
  }

  private RetryAnalysisRequest retryRequest(CreateSourceRecordResponse c) {
    return new RetryAnalysisRequest(c.analysis().analysisId(), "1", UUID.randomUUID());
  }

  private String path(CreateSourceRecordResponse c) {
    return "/api/spaces/" + space.spaceId() + "/source-records/" + c.recordId() + "/analyses";
  }

  private long count(String table, CreateSourceRecordResponse c) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE record_id = ?", Long.class, c.recordId());
  }

  private void expire(UUID id) {
    jdbc.update(
        "UPDATE analysis_run SET lease_until = clock_timestamp() - interval '1 second' WHERE id = ?",
        id);
  }

  private void erase(CreateSourceRecordResponse c, boolean cascade) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> {
              runs.lockSpace(space.spaceId());
              if (cascade) execution.invalidateSource(space.spaceId(), c.recordId());
              jdbc.update(
                  "UPDATE source_record SET lifecycle = 'ERASED', body = NULL, erased_at = now(), version = version + 1 WHERE id = ?",
                  c.recordId());
            });
  }

  private AiAnalysisResponse response(AnalysisExecutionService.Work work, int size) {
    var suggestions = new ArrayList<AiAnalysisResponse.Suggestion>();
    for (int i = 0; i < size; i++)
      suggestions.add(
          new AiAnalysisResponse.Suggestion(
              AiAnalysisResponse.Operation.CREATE,
              CareItemType.OBSERVATION,
              null,
              null,
              List.of(),
              "관찰 " + i,
              "원본 소식",
              Map.of("content", "원본 소식")));
    return new AiAnalysisResponse(work.request().requestId(), suggestions);
  }

  private OAuth2AuthenticationToken login(UUID id) {
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    return new OAuth2AuthenticationToken(
        new MemberOAuth2User(
            id, new DefaultOAuth2User(authorities, Map.of("id", id.toString()), "id")),
        authorities,
        "kakao");
  }

  private static void assertCode(Runnable runnable, ErrorCode code) {
    assertThatThrownBy(runnable::run)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
  }
}
