package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.hanmaum.backend.ai.client.HanmaumAiClient;
import com.hanmaum.backend.ai.client.v2.HanmaumAiV2Client;
import com.hanmaum.backend.analysis.code.AnalysisFailureCode;
import com.hanmaum.backend.analysis.dto.RetryAnalysisRequest;
import com.hanmaum.backend.analysis.entity.AnalysisStatus;
import com.hanmaum.backend.analysis.repository.AnalysisRepository;
import com.hanmaum.backend.analysis.service.*;
import com.hanmaum.backend.global.security.oauth.MemberOAuth2User;
import com.hanmaum.backend.proposal.dto.*;
import com.hanmaum.backend.proposal.repository.AnalysisProposalRepository;
import com.hanmaum.backend.proposal.service.ProposalQueryService;
import com.hanmaum.backend.record.repository.SourceRecordRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Shared C-* expectations through production execution, real HTTP and an isolated PostgreSQL. */
@SpringBootTest(
    properties = {
      "app.analysis.worker.enabled=false",
      "app.analysis.contract-version=v2",
      "app.ai.v2.max-request-bytes=1048576",
      "app.ai.v2.max-response-bytes=524288",
      "app.ai.api-key=fixture-only-not-a-secret",
      "spring.datasource.hikari.maximum-pool-size=4"
    })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AnalysisV2IntegrationTests {
  static final Path FIXTURES = Path.of("src/test/resources/ai-contract/v2");
  static final ObjectMapper JSON = JsonMapper.builder().build();
  static final HttpServer HTTP = startServer();
  static volatile java.util.function.Consumer<com.sun.net.httpserver.HttpExchange> handler;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("app.ai.base-url", () -> "http://127.0.0.1:" + HTTP.getAddress().getPort());
  }

  static HttpServer startServer() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> handler.accept(exchange));
      server.start();
      return server;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @AfterAll
  static void stopServer() {
    HTTP.stop(0);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc mvc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired SourceRecordRepository records;
  @Autowired AnalysisRepository runs;
  @Autowired AnalysisSubmissionService submissions;
  @Autowired AnalysisExecutionService execution;
  @Autowired AnalysisProcessor processor;
  @Autowired ProposalQueryService queries;
  @MockitoSpyBean AnalysisProposalRepository storage;
  @MockitoSpyBean HanmaumAiV2Client client;
  @MockitoBean HanmaumAiClient legacy;
  @MockitoBean AnalysisContractSettings contracts;
  UUID space, record, owner, requester, membership, run;
  JsonNode request;
  AtomicInteger calls = new AtomicInteger();
  AtomicReference<Throwable> httpFailure = new AtomicReference<>();
  List<String> paths = new ArrayList<>();

  static Stream<JsonNode> scenarios() throws Exception {
    return JSON.readTree(Files.readAllBytes(FIXTURES.resolve("cases/backend.json"))).valueStream();
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> namedScenarios() throws Exception {
    return scenarios()
        .map(
            c ->
                org.junit.jupiter.params.provider.Arguments.of(
                    Named.of(c.path("caseId").asText(), c)));
  }

  @BeforeEach
  void prepare() {
    // Dedicated Testcontainers context for this class; never the developer's database.
    jdbc.execute("TRUNCATE app_user CASCADE");
    when(contracts.version()).thenReturn("v2");
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return call.callRealMethod();
            })
        .when(client)
        .analyze(any());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("namedScenarios")
  void executesSharedBackendCase(JsonNode scenario) throws Exception {
    request = read(scenario.path("request").path("bodyFile").asText());
    seed(scenario);
    String beforeItems = itemsSnapshot();
    accept(scenario);
    events(scenario, "AFTER_ACCEPTANCE");
    var work = execution.claim(space, run, Duration.ofMinutes(2)).orElseThrow();
    var input = mapper.valueToTree(work.v2Request());
    assertInput(input);
    for (var event : scenario.path("events"))
      if (event.path("action").asText().equals("FAIL_PROPOSAL_INSERT")) {
        doThrow(new DataAccessResourceFailureException("synthetic storage failure"))
            .when(
                org.springframework.test.util.AopTestUtils
                    .<AnalysisProposalRepository>getUltimateTargetObject(storage))
            .insertV2(any(), eq(event.path("proposalIndex").asInt()), any());
      }
    handler =
        exchange -> {
          try {
            calls.incrementAndGet();
            paths.add(exchange.getRequestURI().getPath());
            assertThat(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"))
                .isEqualTo("fixture-only-not-a-secret");
            assertThat(exchange.getRequestHeaders().getFirst("X-Request-Id"))
                .isEqualTo(request.path("requestId").asText());
            assertInput(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            events(scenario, "BEFORE_COMPLETION");
            byte[] response =
                scenario.path("aiResponse").has("bodyFile")
                    ? Files.readAllBytes(
                        FIXTURES.resolve(scenario.path("aiResponse").path("bodyFile").asText()))
                    : mapper.writeValueAsBytes(scenario.path("aiResponse").path("body"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(
                scenario.path("aiResponse").path("httpStatus").asInt(), response.length);
            exchange.getResponseBody().write(response);
          } catch (Throwable t) {
            httpFailure.set(t);
          } finally {
            exchange.close();
          }
        };
    processor.process(work);
    assertThat(httpFailure.get()).isNull();
    assertThat(calls).hasValue(1);
    verifyNoInteractions(legacy);
    JsonNode expected = scenario.path("expected").path("be");
    var completed = runs.find(space, record, run).orElseThrow();
    String state =
        expected.has("completionState")
            ? expected.path("completionState").asText()
            : expected.path("state").asText();
    assertThat(completed.status().name()).isEqualTo(state);
    if (expected.has("failureCode"))
      assertThat(completed.failureCode() == null ? null : completed.failureCode().name())
          .isEqualTo(
              expected.path("failureCode").isNull() ? null : expected.path("failureCode").asText());
    int count =
        expected.has("storedAtCompletion")
            ? expected.path("storedAtCompletion").asInt()
            : expected.path("storedProposalCount").asInt();
    assertThat(count("ai_proposal")).isEqualTo(count);
    if (expected.has("runContractVersion"))
      assertThat(completed.contractVersion())
          .isEqualTo(expected.path("runContractVersion").asText());
    if (expected.has("invokedPaths"))
      assertThat(paths).containsExactlyElementsOf(strings(expected.path("invokedPaths")));
    if (expected.has("storedSuggestionsFile"))
      assertStored(read(expected.path("storedSuggestionsFile").asText()));
    if (expected.has("careItemWrites")) assertThat(itemsSnapshot()).isEqualTo(beforeItems);
    if (expected.has("transactionRolledBack")) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM content_document WHERE kind='AI_OUTPUT'", Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM evidence WHERE record_id=?", Integer.class, record))
          .isZero();
    }
    if (completed.status() == AnalysisStatus.COMPLETED)
      assertPublicSuggestions(read(scenario.path("aiResponse").path("bodyFile").asText()));
    events(scenario, "AFTER_COMPLETION");
    var result =
        mvc.perform(
                get("/api/spaces/" + space + "/source-records/" + record + "/analyses/" + run)
                    .with(authentication(login(owner))))
            .andReturn()
            .getResponse();
    assertThat(result.getStatus()).isEqualTo(expected.path("queryHttpStatus").asInt());
    JsonNode publicBody = mapper.readTree(result.getContentAsByteArray());
    if (expected.has("queryErrorCode"))
      assertThat(publicBody.path("code").asText())
          .isEqualTo(expected.path("queryErrorCode").asText());
    if (expected.has("failureCodePolicy")) {
      assertThat(completed.failureCode()).isEqualTo(AnalysisFailureCode.AI_SERVICE_UNAVAILABLE);
      assertThat(publicBody.path("data").path("failure").path("code").asText())
          .isEqualTo("AI_SERVICE_UNAVAILABLE");
      assertThat(publicBody.path("data").path("failure").path("message").asText())
          .isEqualTo(AnalysisFailureCode.AI_SERVICE_UNAVAILABLE.message());
    }
    if (expected.has("visibleValidProposalCount")) {
      var visible = queries.list(owner, space, record.toString(), "REVIEW_PENDING", null, 50);
      assertThat(visible.items()).hasSize(expected.path("visibleValidProposalCount").asInt());
      assertThat(publicBody.path("data").path("proposalIds").size())
          .isEqualTo(expected.path("visibleValidProposalCount").asInt());
      assertThat(
              proposalIds().stream()
                  .map(id -> queries.detail(owner, space, id))
                  .mapToInt(d -> d.comparisonBasis().size() + d.evidence().size())
                  .sum())
          .isEqualTo(expected.path("visibleValidBasisCount").asInt());
    }
    if (expected.has("currentValues") || expected.has("mergedValues"))
      for (UUID id : proposalIds()) {
        var detail = queries.detail(owner, space, id);
        if (expected.has("currentValues") || detail.status() == ProposalStatus.INVALIDATED)
          assertThat(detail.currentValues()).isNull();
        // proposedValues is a patch, never a merge with an ambiguous or changed target.
        if (detail.status() == ProposalStatus.INVALIDATED)
          assertThat(detail.proposedValues()).isNull();
        else assertThat(detail.targetItemId()).isNull();
      }
    if (expected.has("deletedSourceTextStoredInDerivedCopies")) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM content_document WHERE kind='AI_OUTPUT' AND body IS NOT NULL",
                  Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM evidence WHERE record_id=? AND quote IS NOT NULL",
                  Integer.class,
                  record))
          .isZero();
      assertThat(queries.list(owner, space, null, "REVIEW_PENDING", null, 50).items()).isEmpty();
      for (UUID id : proposalIds()) {
        var detail = queries.detail(owner, space, id);
        assertThat(detail.proposedValues()).isNull();
        assertThat(detail.currentValues()).isNull();
        assertThat(detail.evidence()).isEmpty();
        assertThat(detail.comparisonBasis()).isEmpty();
      }
    }
    assertDirectPostconditions(scenario, beforeItems);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(com.hanmaum.backend.ai.code.AiFailureCode.class)
  void persistsEveryV2TransportFailureWithoutCallingLegacy(
      com.hanmaum.backend.ai.code.AiFailureCode failure) throws Exception {
    var scenario =
        scenarios()
            .filter(c -> c.path("caseId").asText().equals("C-empty"))
            .findFirst()
            .orElseThrow();
    request = read(scenario.path("request").path("bodyFile").asText());
    seed(scenario);
    accept(scenario);
    var work = execution.claim(space, run, Duration.ofMinutes(2)).orElseThrow();
    doThrow(new com.hanmaum.backend.ai.client.v2.AiV2Exception(failure))
        .when(client)
        .analyze(any());
    processor.process(work);
    var result = runs.find(space, record, run).orElseThrow();
    assertThat(result.status()).isEqualTo(AnalysisStatus.FAILED);
    assertThat(result.failureCode().name()).isEqualTo(failure.name());
    assertThat(count("ai_proposal")).isZero();
    verifyNoInteractions(legacy);
    assertThat(
            jdbc.queryForObject(
                "SELECT body FROM source_record WHERE id=? AND lifecycle='ACTIVE'",
                String.class,
                record))
        .isEqualTo(request.path("content").asText());
  }

  @Test
  void rejectsTooManyCandidatesBeforeHttpWithoutTruncatingOrLosingSource() throws Exception {
    var scenario = scenarios().findFirst().orElseThrow();
    request = read(scenario.path("request").path("bodyFile").asText());
    seed(scenario);
    accept(scenario);
    UUID template = id(request.path("candidates").get(0), "itemId");
    for (int i = 6; i < 101; i++) {
      UUID item = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO care_item (id,space_id,item_type,version,current_document_id,status,created_by_membership_id,created_at,updated_at) SELECT ?,space_id,item_type,version,current_document_id,status,created_by_membership_id,created_at,updated_at FROM care_item WHERE id=?",
          item,
          template);
      jdbc.update(
          "INSERT INTO care_item_basis (id,space_id,item_id,direct_document_id,created_at) SELECT ?,space_id,?,current_document_id,now() FROM care_item WHERE id=?",
          UUID.randomUUID(),
          item,
          template);
    }
    assertThat(execution.claim(space, run, Duration.ofMinutes(2))).isEmpty();
    assertThat(runs.find(space, record, run).orElseThrow().failureCode())
        .isEqualTo(AnalysisFailureCode.AI_INTEGRATION_ERROR);
    assertThat(count("analysis_input_item")).isZero();
    assertThat(count("ai_proposal")).isZero();
    verifyNoInteractions(client, legacy);
    assertThat(
            jdbc.queryForObject(
                "SELECT body FROM source_record WHERE id=? AND lifecycle='ACTIVE'",
                String.class,
                record))
        .isEqualTo(request.path("content").asText());
  }

  @Test
  void preservesQueuedV1AfterActiveVersionChangesAndDoesNotCallV2() throws Exception {
    var scenario = scenarios().findFirst().orElseThrow();
    request = read(scenario.path("request").path("bodyFile").asText());
    seed(scenario);
    when(contracts.version()).thenReturn("v1");
    tx(
        () ->
            run =
                submissions
                    .enqueueInitial(
                        records.findActive(space, record).orElseThrow(), requester, null)
                    .analysisId());
    when(contracts.version()).thenReturn("v2");
    var work = execution.claim(space, run, Duration.ofMinutes(2)).orElseThrow();
    assertThat(work.v2Request()).isNull();
    when(legacy.analyze(any()))
        .thenReturn(
            new com.hanmaum.backend.ai.dto.AiAnalysisResponse(
                work.request().requestId(), List.of()));
    processor.process(work);
    assertThat(runs.find(space, record, run).orElseThrow().status())
        .isEqualTo(AnalysisStatus.COMPLETED);
    verifyNoInteractions(client);
    verify(legacy, times(1)).analyze(any());
  }

  @ParameterizedTest
  @CsvSource({"CURRENT,false", "CURRENT,true", "BASIS,false", "BASIS,true"})
  void discardsCopiedV2ContentThroughDirectDocumentMutationHook(
      String dependency, boolean alreadyCompleted) throws Exception {
    var scenario =
        scenarios()
            .filter(c -> c.path("caseId").asText().equals("C-completed"))
            .findFirst()
            .orElseThrow();
    request = read(scenario.path("request").path("bodyFile").asText());
    seed(scenario);
    accept(scenario);
    var work = execution.claim(space, run, Duration.ofMinutes(2)).orElseThrow();
    var response =
        new com.hanmaum.backend.ai.client.v2.AiV2Contract(
                new com.hanmaum.backend.ai.client.v2.AiV2PayloadLimits(1048576, 524288))
            .parseResponse(
                work.v2Request(),
                Files.readAllBytes(FIXTURES.resolve("payloads/responses/all-operations.json")));
    if (alreadyCompleted) {
      execution.completeV2(work, response);
      assertThat(count("ai_proposal")).isEqualTo(4);
    }
    UUID item = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000104");
    UUID currentDocument =
        jdbc.queryForObject(
            "SELECT current_document_id FROM care_item WHERE id=?", UUID.class, item);
    UUID basisDocument =
        jdbc.queryForObject(
            "SELECT direct_document_id FROM care_item_basis WHERE id=?",
            UUID.class,
            UUID.fromString("aaaaaaaa-0000-4000-8000-000000000304"));
    assertThat(currentDocument).isNotEqualTo(basisDocument);
    UUID document = dependency.equals("CURRENT") ? currentDocument : basisDocument;
    if (dependency.equals("CURRENT"))
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM analysis_input_basis WHERE direct_document_id=?",
                  Integer.class,
                  document))
          .isZero();
    tx(
        () -> {
          execution.invalidateDirectDocument(space, document);
          jdbc.update(
              "UPDATE content_document SET body=NULL,redacted_at=now() WHERE id=?", document);
        });
    assertThat(runs.find(space, record, run).orElseThrow().status())
        .isEqualTo(AnalysisStatus.DISCARDED);
    // A late response must not restore content removed by the mutation hook.
    execution.completeV2(work, response);
    assertThat(count("ai_proposal")).isEqualTo(alreadyCompleted ? 4 : 0);
    assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*) FROM ai_proposal p
                JOIN content_document d ON d.id=p.output_document_id
                JOIN evidence e ON e.id=p.primary_evidence_id
                WHERE p.run_id=? AND (d.body IS NOT NULL OR d.redacted_at IS NULL
                  OR e.quote IS NOT NULL OR e.redacted_at IS NULL OR p.invalidated_at IS NULL)
                """,
                Integer.class,
                run))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT body IS NOT NULL AND redacted_at IS NULL FROM content_document WHERE id=?",
                Boolean.class,
                dependency.equals("CURRENT") ? basisDocument : currentDocument))
        .isTrue();
    for (UUID id : proposalIds())
      assertThat(queries.detail(owner, space, id).proposedValues()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACTIVE", "ENDED", "OTHER_SPACE", "MISSING", "INVALID", "NONE"})
  void projectsOnlyActiveSameSpaceAssigneesIntoAiInputAndPublicCurrentValues(String state)
      throws Exception {
    var scenario =
        scenarios()
            .filter(c -> c.path("caseId").asText().equals("C-completed"))
            .findFirst()
            .orElseThrow();
    request = read(scenario.path("request").path("bodyFile").asText());
    seed(scenario);
    UUID assignee = UUID.randomUUID();
    if (state.equals("ACTIVE") || state.equals("ENDED") || state.equals("OTHER_SPACE")) {
      UUID assigneeUser = user();
      UUID assignedSpace = state.equals("OTHER_SPACE") ? UUID.randomUUID() : space;
      tx(
          () -> {
            if (state.equals("OTHER_SPACE"))
              jdbc.update(
                  "INSERT INTO care_space (id,owner_membership_id,owner_user_id,subject_label,created_at,updated_at) VALUES (?,?,?,'other',now(),now())",
                  assignedSpace,
                  assignee,
                  assigneeUser);
            jdbc.update(
                "INSERT INTO space_membership (id,space_id,user_id,joined_at) VALUES (?,?,?,now())",
                assignee,
                assignedSpace,
                assigneeUser);
          });
      if (state.equals("ENDED"))
        jdbc.update(
            "UPDATE space_membership SET ended_at=now(),end_reason='LEFT' WHERE id=?", assignee);
    }
    String storedAssignee =
        switch (state) {
          case "NONE" -> null;
          case "INVALID" -> "not-a-uuid";
          default -> assignee.toString().toUpperCase(Locale.ROOT);
        };
    UUID task = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000101");
    jdbc.update(
        "UPDATE content_document d SET body=jsonb_set(body,'{fields,assigneeMembershipId}',?::jsonb) FROM care_item i WHERE i.id=? AND d.id=i.current_document_id",
        mapper.writeValueAsString(storedAssignee),
        task);
    accept(scenario);
    var work = execution.claim(space, run, Duration.ofMinutes(2)).orElseThrow();
    var candidate =
        work.v2Request().candidates().stream()
            .filter(c -> c.itemId().equals(task))
            .findFirst()
            .orElseThrow();
    UUID expectedAssignee = state.equals("ACTIVE") ? assignee : null;
    assertThat(
            ((com.hanmaum.backend.ai.dto.v2.AiV2CurrentValues.Task) candidate.currentValues())
                .assigneeMembershipId())
        .isEqualTo(expectedAssignee);
    var output = read("payloads/responses/all-operations.json");
    var update = (ObjectNode) output.path("suggestions").get(1);
    update.put("type", "TASK").put("targetItemId", task.toString());
    var reference = (ObjectNode) update.path("basisReferences").get(0);
    reference.put("itemId", task.toString());
    reference.putArray("basisIds").add("aaaaaaaa-0000-4000-8000-000000000201");
    var contract =
        new com.hanmaum.backend.ai.client.v2.AiV2Contract(
            new com.hanmaum.backend.ai.client.v2.AiV2PayloadLimits(1048576, 524288));
    execution.completeV2(
        work, contract.parseResponse(work.v2Request(), mapper.writeValueAsBytes(output)));
    assertThat(runs.find(space, record, run).orElseThrow().status())
        .isEqualTo(AnalysisStatus.COMPLETED);
    var current = queries.detail(owner, space, proposalIds().get(1)).currentValues();
    assertThat(
            ((com.hanmaum.backend.proposal.dto.ProposalValues.V2Task) current)
                .assigneeMembershipId())
        .isEqualTo(expectedAssignee);
  }

  @Test
  void excludesCurrentValuesDerivedFromAlreadyRedactedOriginalDirectInput() throws Exception {
    var scenario = scenarios().findFirst().orElseThrow();
    request = read(scenario.path("request").path("bodyFile").asText());
    seed(scenario);
    accept(scenario);
    UUID basis = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000300");
    jdbc.update(
        "UPDATE content_document d SET body=NULL,redacted_at=now() FROM care_item_basis b WHERE b.id=? AND d.id=b.direct_document_id",
        basis);
    var work = execution.claim(space, run, Duration.ofMinutes(2)).orElseThrow();
    assertThat(work.v2Request().candidates())
        .hasSize(5)
        .noneMatch(c -> c.itemId().equals(UUID.fromString("aaaaaaaa-0000-4000-8000-000000000100")));
    verifyNoInteractions(client, legacy);
  }

  void seed(JsonNode scenario) {
    space = id(request, "careSpaceId");
    record = id(request, "recordId");
    owner = user();
    requester = user();
    membership = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000900");
    UUID ownerMember = UUID.randomUUID();
    tx(
        () -> {
          jdbc.update(
              "INSERT INTO care_space (id,owner_membership_id,owner_user_id,subject_label,created_at,updated_at) VALUES (?,?,?,'fixture',now(),now())",
              space,
              ownerMember,
              owner);
          jdbc.update(
              "INSERT INTO space_membership (id,space_id,user_id,joined_at) VALUES (?,?,?,now()),(?,?,?,now())",
              ownerMember,
              space,
              owner,
              membership,
              space,
              requester);
        });
    insertSource(
        record,
        request.path("content").asText(),
        OffsetDateTime.parse(request.path("recordedAt").asText()).toInstant());
    for (var item : request.path("candidates")) {
      UUID itemId = id(item, "itemId"), doc = UUID.randomUUID();
      JsonNode setup = null;
      for (var s : scenario.path("setup").path("directInputDocuments"))
        if (id(s, "itemId").equals(itemId)) setup = s;
      if (setup != null) doc = id(setup, "currentDocumentId");
      ObjectNode current = (ObjectNode) item.path("currentValues");
      var body = mapper.createObjectNode();
      body.set("title", current.get("title"));
      body.set("description", current.get("description"));
      var fields = current.deepCopy();
      fields.remove(List.of("title", "description", "status", "answerText"));
      body.set("fields", fields);
      document(doc, body.toString());
      jdbc.update(
          "INSERT INTO care_item (id,space_id,item_type,version,current_document_id,status,created_by_membership_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?,now(),now())",
          itemId,
          space,
          item.path("type").asText(),
          item.path("version").asLong(),
          doc,
          current.has("status") ? current.path("status").asText() : null,
          membership);
      for (var basis : item.path("basis")) {
        UUID direct = null, evidence = null;
        if (basis.path("kind").asText().equals("DIRECT_INPUT")) {
          direct =
              setup != null && id(setup, "basisId").equals(id(basis, "basisId"))
                  ? id(setup, "basisDocumentId")
                  : UUID.randomUUID();
          document(direct, basis.path("text").asText());
        } else {
          UUID source = UUID.randomUUID();
          evidence = UUID.randomUUID();
          insertSource(source, basis.path("text").asText(), Instant.parse("2026-10-15T00:00:00Z"));
          jdbc.update(
              "INSERT INTO evidence (id,space_id,record_id,record_version,quote,created_at) VALUES (?,?,?,1,?,now())",
              evidence,
              space,
              source,
              basis.path("text").asText());
        }
        jdbc.update(
            "INSERT INTO care_item_basis (id,space_id,item_id,direct_document_id,source_evidence_id,submitted_by_membership_id,created_at) VALUES (?,?,?,?,?,?,now())",
            id(basis, "basisId"),
            space,
            itemId,
            direct,
            evidence,
            membership);
      }
    }
  }

  void accept(JsonNode scenario) {
    boolean retry = scenario.path("caseId").asText().equals("C-explicit-retry");
    if (retry) {
      when(contracts.version()).thenReturn("v1");
      tx(
          () ->
              run =
                  submissions
                      .enqueueInitial(
                          records.findActive(space, record).orElseThrow(), requester, null)
                      .analysisId());
      UUID prior = run;
      var seeded = runs.find(space, record, prior).orElseThrow();
      runs.finish(seeded, AnalysisStatus.FAILED, AnalysisFailureCode.AI_SERVICE_UNAVAILABLE);
      jdbc.update(
          "UPDATE analysis_run SET request_id=? WHERE id=?",
          UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001"),
          prior);
      when(contracts.version()).thenReturn("v2");
      run =
          submissions
              .retry(
                  requester, space, record, new RetryAnalysisRequest(prior, "1", UUID.randomUUID()))
              .analysisId();
      assertThat(runs.find(space, record, run).orElseThrow().requestId())
          .isNotEqualTo(seeded.requestId());
      assertThat(runs.find(space, record, run).orElseThrow().inputRecordedAt())
          .isEqualTo(seeded.inputRecordedAt());
      assertThat(runs.find(space, record, prior).orElseThrow().contractVersion()).isEqualTo("v1");
    } else
      tx(
          () ->
              run =
                  submissions
                      .enqueueInitial(
                          records.findActive(space, record).orElseThrow(), requester, null)
                      .analysisId());
    assertThat(runs.find(space, record, run).orElseThrow().contractVersion()).isEqualTo("v2");
    // Bind generated execution ID to the shared synthetic response ID before any network call.
    jdbc.update("UPDATE analysis_run SET request_id=? WHERE id=?", id(request, "requestId"), run);
  }

  void events(JsonNode scenario, String phase) {
    for (var e : scenario.path("events"))
      if (e.path("phase").asText().equals(phase))
        switch (e.path("action").asText()) {
          case "CHANGE_ACTIVE_CONTRACT" ->
              when(contracts.version()).thenReturn(e.path("newVersion").asText());
          case "DELETE_SOURCE_RECORD" ->
              tx(
                  () -> {
                    execution.invalidateSource(space, id(e, "recordId"));
                    jdbc.update(
                        "UPDATE source_record SET lifecycle='ERASED',body=NULL,erased_at=now(),version=version+1 WHERE id=?",
                        id(e, "recordId"));
                  });
          case "DETACH_BASIS" -> {
            var before =
                jdbc.queryForMap("SELECT * FROM care_item_basis WHERE id=?", id(e, "basisId"));
            assertThat(
                    jdbc.update(
                        "DELETE FROM care_item_basis WHERE id=? AND item_id=?",
                        id(e, "basisId"),
                        id(e, "itemId")))
                .isEqualTo(1);
            if (before.get("direct_document_id") != null)
              assertThat(
                      jdbc.queryForObject(
                          "SELECT body IS NOT NULL AND redacted_at IS NULL FROM content_document WHERE id=?",
                          Boolean.class,
                          before.get("direct_document_id")))
                  .isTrue();
            else
              assertThat(
                      jdbc.queryForObject(
                          "SELECT e.quote IS NOT NULL AND e.redacted_at IS NULL AND r.lifecycle='ACTIVE' FROM evidence e JOIN source_record r ON r.id=e.record_id WHERE e.id=?",
                          Boolean.class,
                          before.get("source_evidence_id")))
                  .isTrue();
          }
          case "INVALIDATE_DIRECT_DOCUMENT" ->
              jdbc.update(
                  "UPDATE content_document SET body=NULL,redacted_at=now() WHERE id=?",
                  id(e, "documentId"));
          case "CHANGE_CANDIDATE_VERSION" ->
              jdbc.update(
                  "UPDATE care_item SET version=? WHERE id=?",
                  e.path("newVersion").asLong(),
                  id(e, "itemId"));
          case "END_REQUESTER_MEMBERSHIP" ->
              jdbc.update(
                  "UPDATE space_membership SET ended_at=now(),end_reason='LEFT' WHERE id=?",
                  membership);
          default -> throw new AssertionError("Unhandled fixture event: " + e);
        }
    ;
  }

  void assertInput(JsonNode actual) {
    for (String key : List.of("requestId", "careSpaceId", "recordId", "content", "timezone"))
      assertThat(actual.path(key)).isEqualTo(request.path(key));
    assertThat(OffsetDateTime.parse(actual.path("recordedAt").asText()).toInstant())
        .isEqualTo(OffsetDateTime.parse(request.path("recordedAt").asText()).toInstant());
    assertThat(actual.path("candidates").size()).isEqualTo(request.path("candidates").size());
    for (var expected : request.path("candidates")) {
      var candidate =
          actual
              .path("candidates")
              .valueStream()
              .filter(c -> c.path("itemId").equals(expected.path("itemId")))
              .findFirst()
              .orElseThrow();
      assertThat(candidate.path("currentValues")).isEqualTo(expected.path("currentValues"));
      assertThat(candidate.path("type")).isEqualTo(expected.path("type"));
      assertThat(candidate.path("version").asLong()).isEqualTo(expected.path("version").asLong());
      assertThat(candidate.path("basis")).isEqualTo(expected.path("basis"));
    }
  }

  void assertStored(JsonNode expected) {
    var stored =
        jdbc.queryForList(
            "SELECT d.body::text FROM ai_proposal p JOIN content_document d ON d.id=p.output_document_id WHERE p.run_id=? ORDER BY ordinal_no",
            String.class,
            run);
    for (int n = 0; n < stored.size(); n++)
      assertThat(mapper.readTree(stored.get(n))).isEqualTo(expected.path("suggestions").get(n));
  }

  void assertPublicSuggestions(JsonNode response) {
    var ids = proposalIds();
    for (int n = 0; n < ids.size(); n++) {
      var expected = response.path("suggestions").get(n);
      var detail = queries.detail(owner, space, ids.get(n));
      assertThat(detail.status()).isEqualTo(ProposalStatus.REVIEW_PENDING);
      assertThat(detail.title()).isEqualTo(expected.path("title").asText());
      String action =
          switch (expected.path("operation").asText()) {
            case "CREATE" -> "CREATE_ITEM";
            case "UPDATE" -> "UPDATE_ITEM";
            case "RESOLVE" -> "RESOLVE_QUESTION";
            default -> "LINK_EVIDENCE";
          };
      assertThat(detail.action().name()).isEqualTo(action);
      assertThat(detail.changedFields())
          .containsExactlyInAnyOrderElementsOf(expected.path("changes").propertyNames());
      assertThat(detail.clearFields())
          .containsExactlyElementsOf(strings(expected.path("clearFields")));
      assertThat(detail.reviewReasons())
          .containsExactlyElementsOf(strings(expected.path("reviewReasons")));
      assertThat(detail.relationKind()).isEqualTo(expected.path("relationKind").asText());
      assertThat(detail.evidence().getFirst().quote())
          .isEqualTo(expected.path("evidence").asText());
      var projected = mapper.valueToTree(detail.proposedValues());
      expected
          .path("changes")
          .properties()
          .forEach(entry -> assertThat(projected.path(entry.getKey())).isEqualTo(entry.getValue()));
      if (expected.path("targetItemId").isNull()) {
        assertThat(detail.currentValues()).isNull();
        assertThat(detail.analysisTargetVersion()).isNull();
      } else {
        assertThat(detail.targetItemId()).isEqualTo(id(expected, "targetItemId"));
        assertThat(detail.analysisTargetVersion())
            .isEqualTo(expected.path("expectedVersion").asText());
        assertThat(detail.currentValues()).isNotNull();
      }
      var basisIds = new ArrayList<UUID>();
      expected
          .path("basisReferences")
          .forEach(b -> b.path("basisIds").forEach(i -> basisIds.add(UUID.fromString(i.asText()))));
      assertThat(detail.comparisonBasis())
          .extracting(ProposalDetail.ComparisonBasis::basisId)
          .containsExactlyInAnyOrderElementsOf(basisIds);
    }
  }

  void assertDirectPostconditions(JsonNode scenario, String beforeItems) {
    var expected = scenario.path("expected").path("be");
    if (expected.has("currentDocumentUnchanged"))
      assertThat(itemsSnapshot()).isEqualTo(beforeItems);
    for (var setup : scenario.path("setup").path("directInputDocuments")) {
      var after = expected.path("basisAfterEvent");
      assertThat(
              jdbc.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM care_item_basis WHERE id=?)",
                  Boolean.class,
                  id(setup, "basisId")))
          .isEqualTo(after.path("careItemBasisRowExists").asBoolean());
      assertThat(
              jdbc.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM content_document WHERE id=?)",
                  Boolean.class,
                  id(setup, "basisDocumentId")))
          .isEqualTo(after.path("documentExists").asBoolean());
      assertThat(
              jdbc.queryForObject(
                  "SELECT body IS NOT NULL AND redacted_at IS NULL FROM content_document WHERE id=?",
                  Boolean.class,
                  id(setup, "basisDocumentId")))
          .isEqualTo(after.path("documentValid").asBoolean());
    }
  }

  String itemsSnapshot() {
    return jdbc.queryForObject(
        "SELECT jsonb_agg(jsonb_build_array(i,d) ORDER BY i.id)::text FROM care_item i JOIN content_document d ON d.id=i.current_document_id",
        String.class);
  }

  List<UUID> proposalIds() {
    return jdbc.queryForList(
        "SELECT id FROM ai_proposal WHERE run_id=? ORDER BY ordinal_no", UUID.class, run);
  }

  int count(String table) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE run_id=?", Integer.class, run);
  }

  static UUID id(JsonNode n, String field) {
    return UUID.fromString(n.path(field).asText());
  }

  static List<String> strings(JsonNode n) {
    return n.valueStream().map(JsonNode::asText).toList();
  }

  JsonNode read(String path) throws Exception {
    return mapper.readTree(Files.readAllBytes(FIXTURES.resolve(path)));
  }

  void tx(Runnable r) {
    new TransactionTemplate(transactions).executeWithoutResult(s -> r.run());
  }

  void document(UUID id, String body) {
    jdbc.update(
        "INSERT INTO content_document(id,space_id,kind,body,created_at) VALUES (?,?,'DIRECT_INPUT',?::jsonb,now())",
        id,
        space,
        body);
  }

  void insertSource(UUID id, String body, Instant at) {
    jdbc.update(
        "INSERT INTO source_record(id,space_id,author_membership_id,body,recorded_at) VALUES (?,?,?,?,?)",
        id,
        space,
        membership,
        body,
        Timestamp.from(at));
  }

  UUID user() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO app_user(id,display_name,created_at,updated_at) VALUES (?,'fixture',now(),now())",
        id);
    jdbc.update(
        "INSERT INTO social_account(id,user_id,provider,provider_user_id,created_at) VALUES (?,?,'KAKAO',?,now())",
        UUID.randomUUID(),
        id,
        id.toString());
    return id;
  }

  OAuth2AuthenticationToken login(UUID id) {
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    return new OAuth2AuthenticationToken(
        new MemberOAuth2User(
            id, new DefaultOAuth2User(authorities, Map.of("id", id.toString()), "id")),
        authorities,
        "kakao");
  }
}
