package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hanmaum.backend.ai.dto.CareItemType;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisResponse;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisResponse.*;
import com.hanmaum.backend.analysis.service.AnalysisExecutionService;
import com.hanmaum.backend.careitem.dto.*;
import com.hanmaum.backend.careitem.repository.CareItemProvenanceRepository;
import com.hanmaum.backend.careitem.service.*;
import com.hanmaum.backend.carespace.dto.CreateCareSpaceRequest;
import com.hanmaum.backend.carespace.service.CareSpaceCreationService;
import com.hanmaum.backend.global.security.oauth.MemberOAuth2User;
import com.hanmaum.backend.proposal.service.*;
import com.hanmaum.backend.record.dto.CreateSourceRecordRequest;
import com.hanmaum.backend.record.dto.CreateSourceRecordResponse;
import com.hanmaum.backend.record.service.SourceRecordCreationService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
    properties = {
      "app.analysis.contract-version=v2",
      "app.analysis.worker.enabled=false",
      "app.ai.v2.max-request-bytes=1048576",
      "app.ai.v2.max-response-bytes=524288"
    })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CareItemProvenanceIntegrationTests {
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired CareSpaceCreationService spaces;
  @Autowired CareItemCreationService create;
  @Autowired DirectCareItemUpdateService update;
  @Autowired EditLockService locks;
  @Autowired SourceRecordCreationService records;
  @Autowired AnalysisExecutionService execution;
  @Autowired ProposalQueryService proposals;
  @Autowired ProposalDecisionService decisions;
  @Autowired ChangeRequestService requests;
  @Autowired ChangeRequestQueryService requestQueries;
  @MockitoSpyBean CareItemProvenanceRepository repository;
  UUID owner, member, space, ownerMembership, membership;

  @BeforeEach
  void setup() {
    owner = user("소유자");
    member = user("수진");
    space = spaces.create(owner, new CreateCareSpaceRequest("돌봄", UUID.randomUUID())).spaceId();
    ownerMembership =
        db.queryForObject(
            "SELECT owner_membership_id FROM care_space WHERE id=?", UUID.class, space);
    membership = UUID.randomUUID();
    db.update(
        "INSERT INTO space_membership(id,space_id,user_id,joined_at) VALUES (?,?,?,now())",
        membership,
        space,
        member);
  }

  @Test
  void directInputAndInitialHistoryPreserveValuesAndMetadata() throws Exception {
    UUID item = schedule("  직접 입력\n그대로 💚  ");
    var evidence = data(member, item, "/evidence").path("items");
    assertThat(evidence.size()).isOne();
    var first = evidence.get(0);
    assertThat(first.path("kind").asText()).isEqualTo("DIRECT_INPUT");
    assertThat(first.path("status").asText()).isEqualTo("VALID");
    assertThat(first.path("recordId").isNull()).isTrue();
    assertThat(mapper.readTree(first.path("text").asText()).path("title").asText())
        .isEqualTo("  직접 입력\n그대로 💚  ");
    assertThat(first.path("createdByMembershipId").asText()).isEqualTo(ownerMembership.toString());
    assertThat(first.path("fieldPaths").isEmpty()).isTrue();
    var history = data(member, item, "/history").path("items");
    assertThat(history.size()).isOne();
    assertThat(history.get(0).path("changeKind").asText()).isEqualTo("CREATE");
    assertThat(history.get(0).at("/changedBy/displayName").asText()).isEqualTo("소유자");
    var detail = data(member, item, "/history/1");
    assertThat(detail.path("previousVersion").isNull()).isTrue();
    assertThat(detail.path("before").isNull()).isTrue();
    assertThat(detail.at("/after/title").asText()).isEqualTo("  직접 입력\n그대로 💚  ");
    assertThat(detail.at("/after/status").asText()).isEqualTo("SCHEDULED");
    assertThat(detail.path("evidenceIds").get(0)).isEqualTo(first.path("evidenceId"));
  }

  @Test
  void actualContentAndStatusWritesHaveSeparateVersionsAndDerivedInputs() throws Exception {
    UUID item = schedule("처음");
    var edit = command(owner, item, 1);
    edit.putObject("values").put("title", "수정");
    update.update(owner, space, item, edit, false);
    update.update(owner, space, item, command(owner, item, 2).put("status", "COMPLETED"), true);
    var history = data(member, item, "/history").path("items");
    assertThat(history).extracting(n -> n.path("version").asText()).containsExactly("3", "2", "1");
    assertThat(history)
        .extracting(n -> n.path("changeKind").asText())
        .containsExactly("STATUS_CHANGE", "UPDATE", "CREATE");
    var detail = data(member, item, "/history/2");
    assertThat(detail.at("/before/title").asText()).isEqualTo("처음");
    assertThat(detail.at("/after/title").asText()).isEqualTo("수정");
    var evidence = data(member, item, "/evidence").path("items");
    assertThat(evidence)
        .extracting(n -> n.path("kind").asText())
        .containsExactly("DIRECT_INPUT", "DERIVED", "DERIVED");
    assertThat(detail.path("evidenceIds"))
        .extracting(JsonNode::asText)
        .containsExactly(evidence.get(1).path("evidenceId").asText());
    assertThat(data(member, item, "/history/1").path("evidenceIds"))
        .extracting(JsonNode::asText)
        .containsExactly(evidence.get(0).path("evidenceId").asText());
  }

  @Test
  void withdrawnAndFormerParticipantsAreDifferentAndPastAssignmentsRemainHistorical()
      throws Exception {
    UUID item =
        create
            .create(
                owner,
                space,
                new CreateCareItemRequest(
                    "TASK",
                    "할 일",
                    null,
                    new CreateCareItemRequest.TaskFields(null, membership.toString()),
                    UUID.randomUUID()))
            .itemId();
    update.update(member, space, item, command(member, item, 1).put("status", "IN_PROGRESS"), true);
    db.update(
        "UPDATE space_membership SET ended_at=now(),end_reason='LEFT' WHERE id=?", membership);
    var previous = data(owner, item, "/history/2");
    assertThat(previous.at("/changedBy/withdrawn").asBoolean()).isFalse();
    assertThat(previous.at("/changedBy/displayName").asText()).isEqualTo("수진");
    assertThat(previous.at("/before/fields/assigneeMembershipId").asText())
        .isEqualTo(membership.toString());
    db.update("UPDATE space_membership SET user_id=NULL WHERE id=?", membership);
    var withdrawn = data(owner, item, "/history/2");
    assertThat(withdrawn.at("/changedBy/withdrawn").asBoolean()).isTrue();
    assertThat(withdrawn.at("/changedBy/membershipId").isNull()).isTrue();
    assertThat(withdrawn.at("/changedBy/displayName").isNull()).isTrue();
  }

  @Test
  void emptyRetainedLinksAndHistoryDoNotBypassAuthorization() throws Exception {
    UUID item = schedule("항목");
    db.update("DELETE FROM care_item_basis WHERE item_id=?", item);
    assertThat(data(member, item, "/evidence").path("items").isEmpty()).isTrue();
    db.update("DELETE FROM care_item_history WHERE item_id=?", item);
    assertThat(data(member, item, "/history").path("items").isEmpty()).isTrue();
    mvc.perform(get(path(item, "/history/1")).with(authentication(login(member))))
        .andExpect(status().isNotFound());
  }

  @ParameterizedTest
  @ValueSource(strings = {"/evidence", "/history", "/history/1"})
  void enforcesSessionCurrentMembershipAndSameSpace(String suffix) throws Exception {
    UUID item = schedule("다른 공간에서 못 읽음");
    mvc.perform(get(path(item, suffix))).andExpect(status().isUnauthorized());
    mvc.perform(get(path(item, suffix)).with(authentication(login(user("비참여자")))))
        .andExpect(status().isForbidden());
    var other =
        spaces.create(member, new CreateCareSpaceRequest("다른 공간", UUID.randomUUID())).spaceId();
    mvc.perform(
            get("/api/spaces/" + other + "/items/" + item + suffix)
                .with(authentication(login(member))))
        .andExpect(status().isNotFound());
    mvc.perform(get(path(UUID.randomUUID(), suffix)).with(authentication(login(member))))
        .andExpect(status().isNotFound());
    db.update(
        "UPDATE space_membership SET ended_at=now(),end_reason='LEFT' WHERE id=?", membership);
    mvc.perform(get(path(item, suffix)).with(authentication(login(member))))
        .andExpect(status().isForbidden());
    db.update("UPDATE care_space SET lifecycle='DELETING' WHERE id=?", space);
    mvc.perform(get(path(item, suffix)).with(authentication(login(owner))))
        .andExpect(status().isNotFound());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "01", "-1", "1.0", "not-a-version", "9223372036854775808"})
  void rejectsInvalidVersionWithoutCoercion(String version) throws Exception {
    UUID item = schedule("버전");
    mvc.perform(get(path(item, "/history/" + version)).with(authentication(login(member))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void erasedAncestorHidesCopiedContentEvenBeforePhysicalDependentErasure() throws Exception {
    UUID item = schedule("삭제할 원문");
    UUID root = document(item);
    var edit = command(owner, item, 1);
    edit.putObject("values").put("title", "복사된 변경 제목");
    update.update(owner, space, item, edit, false);
    db.update("UPDATE content_document SET body=NULL,redacted_at=now() WHERE id=?", root);
    var detail = data(member, item, "/history/2");
    assertThat(detail.path("before").isNull()).isTrue();
    assertThat(detail.path("after").isNull()).isTrue();
    assertThat(detail.path("redactedFields"))
        .extracting(JsonNode::asText)
        .containsExactly("before", "after");
    assertThat(detail.at("/changedBy/displayName").asText()).isEqualTo("소유자");
    var evidence = data(member, item, "/evidence");
    assertThat(evidence.path("items"))
        .allSatisfy(
            row -> {
              assertThat(row.path("status").asText()).isEqualTo("REDACTED");
              assertThat(row.path("text").isNull()).isTrue();
            });
    assertThat(detail.toString() + evidence + data(member, item, "/history"))
        .doesNotContain("삭제할 원문", "복사된 변경 제목");
  }

  @Test
  void independentLaterDocumentIsNotHiddenByRedactedPastVersionOrGeneralDeletion()
      throws Exception {
    UUID item = schedule("과거 내용");
    UUID original = document(item), fresh = UUID.randomUUID();
    db.update("UPDATE content_document SET body=NULL,redacted_at=now() WHERE id=?", original);
    db.update(
        "INSERT INTO content_document(id,space_id,kind,body,created_at) VALUES (?,?,'DIRECT_INPUT',?::jsonb,now())",
        fresh,
        space,
        "{\"title\":\"새로 확인한 사실\",\"fields\":{}}");
    db.update(
        "INSERT INTO care_item_history(space_id,item_id,version,operation,document_id,status,changed_by_membership_id,changed_at) VALUES (?,?,2,'UPDATE',?,'SCHEDULED',?,now())",
        space,
        item,
        fresh,
        ownerMembership);
    db.update(
        "UPDATE care_item SET version=2,current_document_id=?,visibility='DELETED' WHERE id=?",
        fresh,
        item);
    var detail = data(member, item, "/history/2");
    assertThat(detail.path("before").isNull()).isTrue();
    assertThat(detail.at("/after/title").asText()).isEqualTo("새로 확인한 사실");
    assertThat(detail.path("redactedFields"))
        .extracting(JsonNode::asText)
        .containsExactly("before");
    assertThat(data(member, item, "/history").path("items").size()).isEqualTo(2);
  }

  @Test
  void quoteShowsOriginalAuthorAndTimeAndErasedOrVersionChangedSourceNeverLeaks() throws Exception {
    UUID item = schedule("독립 직접 입력");
    var source =
        records.create(member, space, new CreateSourceRecordRequest("새 소식 원문", UUID.randomUUID()));
    UUID evidence = UUID.randomUUID();
    db.update(
        "INSERT INTO evidence(id,space_id,record_id,record_version,quote,created_at) VALUES (?,?,?,1,'소식 원문',now())",
        evidence,
        space,
        source.recordId());
    db.update(
        "INSERT INTO care_item_basis(id,space_id,item_id,source_evidence_id,submitted_by_membership_id,created_at) VALUES (?,?,?,?,?,now()+interval '1 hour')",
        UUID.randomUUID(),
        space,
        item,
        evidence,
        ownerMembership);
    var quote = data(member, item, "/evidence").at("/items/1");
    assertThat(quote.path("evidenceId").asText()).isEqualTo(evidence.toString());
    assertThat(quote.path("createdByMembershipId").asText()).isEqualTo(membership.toString());
    assertThat(quote.path("createdAt").asText()).isEqualTo(source.recordedAt().toString());
    assertThat(quote.path("text").asText()).isEqualTo("소식 원문");
    assertThat(data(member, item, "/history/1").path("evidenceIds"))
        .extracting(JsonNode::asText)
        .doesNotContain(evidence.toString());
    db.update("UPDATE source_record SET version=2 WHERE id=?", source.recordId());
    var blocked = data(member, item, "/evidence").at("/items/1");
    assertThat(blocked.path("text").isNull()).isTrue();
    assertThat(blocked.path("recordId").isNull()).isTrue();
    db.update(
        "UPDATE source_record SET version=1,lifecycle='ERASED',body=NULL,recorded_at=NULL,erased_at=now() WHERE id=?",
        source.recordId());
    assertThat(data(member, item, "/evidence").toString()).doesNotContain("소식 원문");
    assertThat(data(member, item, "/evidence").at("/items/1/createdAt").isNull()).isTrue();
    assertThat(data(member, item, "/evidence").at("/items/0/status").asText()).isEqualTo("VALID");
  }

  @Test
  void appliedAiCreateAndEvidenceLinkIdentifyOnlyTheirOwnChangeEvidence() throws Exception {
    var first = propose("CREATE", null);
    UUID item = apply(first, null);
    String initial = data(member, item, "/history/1").path("evidenceIds").get(0).asText();
    assertThat(data(member, item, "/evidence").at("/items/0/evidenceId").asText())
        .isEqualTo(initial);
    var second = propose("LINK_EVIDENCE", item);
    apply(second, item);
    var detail = data(member, item, "/history/2");
    assertThat(detail.path("before")).isEqualTo(detail.path("after"));
    assertThat(detail.path("evidenceIds").size()).isOne();
    assertThat(detail.path("evidenceIds").get(0).asText()).isNotEqualTo(initial);
    assertThat(data(member, item, "/history/1").path("evidenceIds"))
        .extracting(JsonNode::asText)
        .containsExactly(initial);
    db.update(
        "UPDATE ai_proposal SET invalidated_at=now() WHERE run_id=?",
        second.analysis().analysisId());
    assertThat(data(member, item, "/evidence").at("/items/1/text").isNull()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/evidence", "/history", "/history/1"})
  void everyReadUsesOnePermissionAndDataSnapshotDuringConcurrentDepartureAndRedaction(String suffix)
      throws Exception {
    UUID item = schedule("스냅샷 값");
    doAnswer(
            invocation -> {
              var result = invocation.callRealMethod();
              try (var pool = Executors.newSingleThreadExecutor()) {
                pool.submit(
                        () -> {
                          db.update(
                              "UPDATE space_membership SET ended_at=now(),end_reason='LEFT' WHERE id=?",
                              membership);
                          db.update(
                              "UPDATE content_document SET body=NULL,redacted_at=now() WHERE id=?",
                              document(item));
                        })
                    .get(5, TimeUnit.SECONDS);
              }
              return result;
            })
        .when(repository)
        .itemType(space, item);
    var snapshot = data(member, item, suffix);
    if (suffix.equals("/evidence"))
      assertThat(snapshot.at("/items/0/text").asText()).contains("스냅샷 값");
    else if (suffix.equals("/history"))
      assertThat(snapshot.at("/items/0/redactedFields").isEmpty()).isTrue();
    else assertThat(snapshot.at("/after/title").asText()).isEqualTo("스냅샷 값");
    reset(repository);
    mvc.perform(get(path(item, "/history/1")).with(authentication(login(member))))
        .andExpect(status().isForbidden());
    assertThat(data(owner, item, "/history/1").path("after").isNull()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"SCHEDULE", "TASK", "QUESTION", "OBSERVATION", "GUIDANCE"})
  void comparesAllFiveConfirmedTypesWithoutReturningInternalFields(String type) throws Exception {
    UUID item = apply(propose("CREATE", null, type), null);
    var detail = data(member, item, "/history/1");
    assertThat(detail.at("/after/title").asText()).isEqualTo("확정 항목");
    assertThat(detail.path("before").isNull()).isTrue();
    assertThat(detail.path("evidenceIds").size()).isOne();
    db.update(
        "UPDATE content_document SET body=body || '{\"internalOnly\":\"private metadata\"}'::jsonb WHERE id=?",
        document(item));
    assertThat(data(member, item, "/history/1").toString())
        .doesNotContain("internalOnly", "private metadata");
  }

  @ParameterizedTest
  @ValueSource(strings = {"AI_PROPOSAL", "DIRECT_INPUT"})
  void pendingRequestsStayOutsideHistoryAndApprovalPreservesOriginalInputAndDecisionAuthor(
      String sourceType) throws Exception {
    UUID existing = schedule("이미 확정된 항목");
    var input = mapper.createObjectNode().put("clientRequestId", UUID.randomUUID().toString());
    var entry =
        input
            .putArray("entries")
            .addObject()
            .put("sourceType", sourceType)
            .put("action", "CREATE_ITEM");
    if (sourceType.equals("AI_PROPOSAL")) {
      var source = propose("CREATE", null);
      UUID proposal =
          db.queryForObject(
              "SELECT id FROM ai_proposal WHERE run_id=?",
              UUID.class,
              source.analysis().analysisId());
      entry
          .put("proposalId", proposal.toString())
          .put("reviewToken", proposals.detail(member, space, proposal).reviewToken());
    }
    entry.set(
        "values",
        mapper.readTree(
            "{\"itemType\":\"SCHEDULE\",\"title\":\"구성원 입력\",\"description\":null,\"fields\":{}}"));
    var submitted = requests.submit(member, space, input);
    assertThat(data(member, existing, "/history").path("items").size()).isOne();
    assertThat(
            db.queryForObject("SELECT count(*) FROM care_item WHERE space_id=?", Long.class, space))
        .isOne();
    UUID request = UUID.fromString(submitted.path("requestId").asText());
    var requested = requestQueries.detail(owner, space, request).at("/entries/0");
    var decision = mapper.createObjectNode().put("clientRequestId", UUID.randomUUID().toString());
    decision
        .putArray("entries")
        .addObject()
        .put("requestEntryId", requested.path("requestEntryId").asText())
        .put("reviewToken", requested.path("reviewToken").asText());
    var applied = requests.apply(owner, space, decision);
    UUID item = UUID.fromString(applied.at("/results/0/itemId").asText());
    var detail = data(member, item, "/history/1");
    assertThat(detail.at("/after/title").asText()).isEqualTo("구성원 입력");
    assertThat(detail.at("/changedBy/membershipId").asText()).isEqualTo(ownerMembership.toString());
    assertThat(detail.path("evidenceIds").size()).isOne();
    var evidence = data(member, item, "/evidence").at("/items/0");
    assertThat(detail.path("evidenceIds").get(0)).isEqualTo(evidence.path("evidenceId"));
    if (sourceType.equals("DIRECT_INPUT")) {
      assertThat(evidence.path("createdByMembershipId").asText()).isEqualTo(membership.toString());
      assertThat(evidence.path("text").asText()).contains("구성원 입력");
    }
  }

  @Test
  void exposesTypedOpenApiResponsesWithSessionOnlyReadSecurity() throws Exception {
    var spec =
        mapper.readTree(
            mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray());
    String base = "/api/spaces/{spaceId}/items/{itemId}";
    for (String suffix : List.of("/evidence", "/history", "/history/{version}")) {
      var operation = spec.path("paths").path(base + suffix).path("get");
      assertThat(
              operation
                  .path("responses")
                  .path("200")
                  .at("/content/application~1json/schema/$ref")
                  .asText())
          .startsWith("#/components/schemas/");
      assertThat(operation.path("security").toString())
          .contains("SessionCookie")
          .doesNotContain("CsrfToken");
      assertThat(operation.path("parameters"))
          .noneSatisfy(parameter -> assertThat(parameter.path("name").asText()).isEqualTo("actor"));
      for (String code : List.of("400", "401", "403", "404", "500"))
        assertThat(operation.path("responses").has(code)).isTrue();
    }
  }

  @Test
  void assigneeOnlyEditHasDedicatedHistoryKind() throws Exception {
    UUID item =
        create
            .create(
                owner,
                space,
                new CreateCareItemRequest(
                    "TASK",
                    "할 일",
                    null,
                    new CreateCareItemRequest.TaskFields(null, membership.toString()),
                    UUID.randomUUID()))
            .itemId();
    var edit = command(owner, item, 1);
    edit.putObject("values").putObject("fields").putNull("assigneeMembershipId");
    update.update(owner, space, item, edit, false);
    assertThat(data(member, item, "/history").at("/items/0/changeKind").asText())
        .isEqualTo("ASSIGNEE_CHANGE");
  }

  private CreateSourceRecordResponse propose(String operation, UUID target) {
    return propose(operation, target, "SCHEDULE");
  }

  private CreateSourceRecordResponse propose(String operation, UUID target, String type) {
    var source =
        records.create(owner, space, new CreateSourceRecordRequest("합성 소식 근거", UUID.randomUUID()));
    var work =
        execution.claim(space, source.analysis().analysisId(), Duration.ofMinutes(2)).orElseThrow();
    var candidate =
        target == null
            ? null
            : work.v2Request().candidates().stream()
                .filter(c -> c.itemId().equals(target))
                .findFirst()
                .orElseThrow();
    var suggestion =
        new Suggestion(
            Operation.valueOf(operation),
            CareItemType.valueOf(type),
            target,
            candidate == null ? null : candidate.version(),
            List.of(),
            "AI 일정",
            "합성 소식 근거",
            operation.equals("CREATE") ? Map.of("title", "AI 일정") : Map.of(),
            List.of(),
            operation.equals("CREATE") ? RelationKind.NEW : RelationKind.DUPLICATE,
            List.of(),
            candidate == null
                ? List.of()
                : List.of(
                    new BasisReference(target, List.of(candidate.basis().getFirst().basisId()))));
    assertThat(
            execution.completeV2(
                work, new AiV2AnalysisResponse(work.v2Request().requestId(), List.of(suggestion))))
        .isTrue();
    return source;
  }

  private UUID apply(CreateSourceRecordResponse source, UUID target) {
    UUID proposal =
        db.queryForObject(
            "SELECT id FROM ai_proposal WHERE run_id=?",
            UUID.class,
            source.analysis().analysisId());
    var body = mapper.createObjectNode().put("clientRequestId", UUID.randomUUID().toString());
    var entry =
        body.putArray("entries")
            .addObject()
            .put("proposalId", proposal.toString())
            .put("action", target == null ? "CREATE_ITEM" : "LINK_EVIDENCE")
            .put("reviewToken", proposals.detail(owner, space, proposal).reviewToken());
    if (target == null) {
      String type =
          db.queryForObject("SELECT item_type FROM ai_proposal WHERE id=?", String.class, proposal);
      var values =
          mapper
              .createObjectNode()
              .put("itemType", type)
              .put("title", "확정 항목")
              .putNull("description");
      values.putObject("fields");
      entry.set("values", values);
    } else
      entry
          .put("targetItemId", target.toString())
          .put("expectedItemVersion", "1")
          .put("leaseToken", locks.acquire(owner, space, target, 1).leaseToken());
    return decisions.apply(owner, space, body).results().getFirst().itemId();
  }

  private UUID schedule(String title) {
    return create
        .create(
            owner,
            space,
            new CreateCareItemRequest(
                "SCHEDULE", title, null, new ScheduleFields(null, null, null), UUID.randomUUID()))
        .itemId();
  }

  private UUID document(UUID item) {
    return db.queryForObject(
        "SELECT current_document_id FROM care_item WHERE id=?", UUID.class, item);
  }

  private ObjectNode command(UUID actor, UUID item, long version) {
    return mapper
        .createObjectNode()
        .put("clientRequestId", UUID.randomUUID().toString())
        .put("expectedItemVersion", Long.toString(version))
        .put("leaseToken", locks.acquire(actor, space, item, version).leaseToken());
  }

  private String path(UUID item, String suffix) {
    return "/api/spaces/" + space + "/items/" + item + suffix;
  }

  private JsonNode data(UUID actor, UUID item, String suffix) throws Exception {
    var response =
        mvc.perform(get(path(item, suffix)).with(authentication(login(actor))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse();
    return mapper.readTree(response.getContentAsByteArray()).path("data");
  }

  private UUID user(String name) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO app_user(id,display_name,created_at,updated_at) VALUES (?,?,now(),now())",
        id,
        name);
    db.update(
        "INSERT INTO social_account(id,user_id,provider,provider_user_id,created_at) VALUES (?,?,'KAKAO',?,now())",
        UUID.randomUUID(),
        id,
        id.toString());
    return id;
  }

  private OAuth2AuthenticationToken login(UUID user) {
    var roles = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    return new OAuth2AuthenticationToken(
        new MemberOAuth2User(
            user, new DefaultOAuth2User(roles, Map.of("id", user.toString()), "id")),
        roles,
        "kakao");
  }
}
