package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
      "app.analysis.contract-version=v2",
      "app.analysis.worker.enabled=false",
      "app.ai.v2.max-request-bytes=1048576",
      "app.ai.v2.max-response-bytes=524288",
      "spring.datasource.hikari.maximum-pool-size=4"
    })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ChangeRequestIntegrationTests {
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
  @Autowired PlatformTransactionManager transactions;
  UUID owner, member, space, ownerMembership, memberMembership;

  @BeforeEach
  void setup() {
    owner = user();
    member = user();
    space =
        spaces.create(owner, new CreateCareSpaceRequest("합성 요청 공간", UUID.randomUUID())).spaceId();
    ownerMembership =
        jdbc.queryForObject(
            "SELECT owner_membership_id FROM care_space WHERE id=?", UUID.class, space);
    memberMembership = join(member);
  }

  @Test
  void documentsSuccessfulQueriesAndEvidenceOnlyDecisionValues() throws Exception {
    var specification =
        mapper.readTree(
            mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    for (var endpoint :
        Map.of(
                "/api/spaces/{spaceId}/change-requests", "ChangeRequestPage",
                "/api/spaces/{spaceId}/change-requests/{requestId}", "ChangeRequestDetail")
            .entrySet()) {
      var responseSchema =
          specification
              .path("paths")
              .path(endpoint.getKey())
              .path("get")
              .path("responses")
              .path("200")
              .path("content")
              .path("application/json")
              .path("schema");
      assertThat(responseSchema.path("$ref").asText()).startsWith("#/components/schemas/");
      var envelope = specification.at(responseSchema.path("$ref").asText().substring(1));
      assertThat(envelope.at("/properties/data/$ref").asText())
          .isEqualTo("#/components/schemas/" + endpoint.getValue());
    }
    assertThat(
            specification
                .at(
                    "/components/schemas/ChangeRequestDecision/properties/appliedValues/description")
                .asText())
        .contains("LINK_EVIDENCE", "null");
  }

  @Test
  void submitsWithoutChangingItemsAndOwnerEditsFinalValuesWithFreshReviewAndLease()
      throws Exception {
    UUID item = seedItem("TASK");
    UUID proposal = ids(propose(List.of(update(item, "TASK")))).getFirst();
    var input = batch();
    var entry = memberEntry(member, proposal, item, mapper.readTree("{\"title\":\"구성원 요청\"}"));
    input.withArray("entries").add(entry);
    var submitted = postData(member, "/change-requests", input, 201);
    UUID request = uuid(submitted, "requestId");
    assertThat(submitted.at("/entries/0/status").asText()).isEqualTo("PENDING");
    assertThat(itemQueries.detail(owner, space, item).title()).isEqualTo("기존 제목");
    assertThat(itemQueries.detail(owner, space, item).version()).isEqualTo("1");
    assertThat(itemQueries.detail(owner, space, item).editLock()).isNull();
    assertThat(detail(member, request).at("/entries/0/reviewToken").isNull()).isTrue();

    var decision = approval(request, 0, item);
    ((ObjectNode) decision.withArray("entries").get(0))
        .set("values", mapper.readTree("{\"title\":\"소유자 확정\"}"));
    var applied = postData(owner, "/request-applications", decision, 200);
    assertThat(applied.at("/results/0/itemId").asText()).isEqualTo(item.toString());
    assertThat(applied.at("/results/0/itemVersion").asText()).isEqualTo("2");
    assertThat(itemQueries.detail(member, space, item).title()).isEqualTo("소유자 확정");
    assertThat(itemQueries.detail(owner, space, item).editLock()).isNull();
    var after = detail(member, request).at("/entries/0");
    assertThat(after.path("status").asText()).isEqualTo("APPLIED");
    assertThat(after.at("/requestedValues/title").asText()).isEqualTo("구성원 요청");
    assertThat(after.at("/decision/appliedValues/title").asText()).isEqualTo("소유자 확정");
    assertThat(queries.detail(owner, space, proposal).status()).isEqualTo(ProposalStatus.APPLIED);
    assertThat(count("care_item_history", "item_id", item)).isEqualTo(2);

    assertThat(postData(member, "/change-requests", input, 201)).isEqualTo(submitted);
    assertThat(postData(owner, "/request-applications", decision, 200)).isEqualTo(applied);
    String receipts =
        jdbc.queryForObject(
            "SELECT jsonb_agg(result_refs)::text FROM command_receipt WHERE actor_user_id IN (?,?)",
            String.class,
            member,
            owner);
    assertThat(receipts)
        .doesNotContain(
            "구성원 요청",
            "소유자 확정",
            entry.path("leaseToken").asText(),
            entry.path("reviewToken").asText(),
            decision.at("/entries/0/leaseToken").asText(),
            decision.at("/entries/0/reviewToken").asText());
  }

  @Test
  void allFiveAiCreationTypesCanBeRequestedAndAppliedWithoutOwnerOverrides() throws Exception {
    var specs =
        Arrays.stream(CareItemType.values())
            .map(t -> new Spec("CREATE", t.name(), null, Map.of("title", "AI 원안"), List.<UUID>of()))
            .toList();
    var proposalIds = ids(propose(specs));
    var input = batch();
    for (int i = 0; i < proposalIds.size(); i++)
      input
          .withArray("entries")
          .add(
              memberEntry(
                  member, proposalIds.get(i), null, createValues(specs.get(i).type(), "요청 " + i)));
    UUID request = uuid(postData(member, "/change-requests", input, 201), "requestId");
    assertThat(count("care_item", "space_id", space)).isZero();
    var decision = batch();
    var detail = detail(owner, request);
    for (JsonNode e : detail.path("entries")) decision.withArray("entries").add(decisionEntry(e));
    var applied = postData(owner, "/request-applications", decision, 200);
    assertThat(applied.path("results").size()).isEqualTo(5);
    assertThat(count("care_item", "space_id", space)).isEqualTo(5);
    var finalEntries = detail(member, request).path("entries");
    for (int i = 0; i < 5; i++) {
      assertThat(
              itemQueries
                  .detail(owner, space, uuid(applied.path("results").get(i), "itemId"))
                  .title())
          .isEqualTo("요청 " + i);
      assertThat(finalEntries.get(i).at("/decision/appliedValues/title").asText())
          .isEqualTo("요청 " + i);
    }
  }

  @Test
  void sameMemberCannotDuplicatePendingProposalButOtherMembersCanAndOnlyOneWins() throws Exception {
    UUID proposal = createProposal();
    UUID first = submitCreate(member, proposal);
    var duplicate = batch();
    duplicate
        .withArray("entries")
        .add(memberEntry(member, proposal, null, createValues("SCHEDULE", "다른 수정안")));
    postError(member, "/change-requests", duplicate, 409, "CHANGE_REQUEST_ALREADY_PENDING");
    UUID other = user();
    join(other);
    UUID second = submitCreate(other, proposal);
    postData(owner, "/request-applications", approval(first, 0, null), 200);
    assertThat(detail(other, second).at("/entries/0/status").asText()).isEqualTo("INVALIDATED");
    assertThat(detail(other, second).at("/entries/0/reviewToken").isNull()).isTrue();
    assertThat(count("care_item", "space_id", space)).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"RESOLVE", "LINK_EVIDENCE"})
  void resolvesAndLinksThroughMemberRequestWithExistingMutationSemantics(String operation)
      throws Exception {
    String type = operation.equals("RESOLVE") ? "QUESTION" : "TASK";
    UUID item = seedItem(type);
    var source =
        propose(
            List.of(
                new Spec(
                    operation,
                    type,
                    item,
                    operation.equals("RESOLVE") ? Map.of("answerText", "AI 답변") : Map.of(),
                    List.of())));
    JsonNode values =
        operation.equals("RESOLVE")
            ? mapper.readTree("{\"confirmedAnswer\":\"가족이 확인한 답변\"}")
            : null;
    var input = batch();
    input.withArray("entries").add(memberEntry(member, ids(source).getFirst(), item, values));
    UUID request = uuid(postData(member, "/change-requests", input, 201), "requestId");
    assertThat(itemQueries.detail(owner, space, item).version()).isEqualTo("1");
    postData(owner, "/request-applications", approval(request, 0, item), 200);
    var after = itemQueries.detail(member, space, item);
    assertThat(after.version()).isEqualTo("2");
    if (operation.equals("RESOLVE")) {
      assertThat(after.status()).isEqualTo("CONFIRMED");
      assertThat(after.confirmedAnswer()).isEqualTo("가족이 확인한 답변");
      assertThat(
              detail(member, request)
                  .at("/entries/0/decision/appliedValues/confirmedAnswer")
                  .asText())
          .isEqualTo("가족이 확인한 답변");
    } else {
      assertThat(after.title()).isEqualTo("기존 제목");
      assertThat(count("care_item_basis", "item_id", item)).isEqualTo(2);
    }
  }

  @Test
  void linkEvidenceDecisionHasNoAppliedValuesAndPreservesReferencedItemDocument() throws Exception {
    UUID item = seedItem("TASK");
    UUID originalDocument =
        jdbc.queryForObject(
            "SELECT current_document_id FROM care_item WHERE id=?", UUID.class, item);
    String originalBody =
        jdbc.queryForObject(
            "SELECT body::text FROM content_document WHERE id=?", String.class, originalDocument);
    var source = propose(List.of(new Spec("LINK_EVIDENCE", "TASK", item, Map.of(), List.of())));
    var submission = batch();
    submission.withArray("entries").add(memberEntry(member, ids(source).getFirst(), item, null));
    UUID request = uuid(postData(member, "/change-requests", submission, 201), "requestId");
    var approval = approval(request, 0, item);
    var applied = postData(owner, "/request-applications", approval, 200);

    for (UUID viewer : List.of(owner, member)) {
      var entry = detail(viewer, request).at("/entries/0");
      assertThat(entry.path("status").asText()).isEqualTo("APPLIED");
      assertThat(entry.path("requestedValues").isNull()).isTrue();
      assertThat(entry.at("/decision/appliedValues").isNull()).isTrue();
      assertThat(entry.at("/decision/itemId").asText()).isEqualTo(item.toString());
      assertThat(entry.at("/decision/itemVersion").asText()).isEqualTo("2");
      assertThat(entry.path("redactedFields"))
          .doesNotContain(mapper.valueToTree("decision.appliedValues"));
      // Existing content belongs to the current item view, not to a link-only decision.
      assertThat(entry.at("/currentValues/title").asText()).isEqualTo("기존 제목");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT result_document_id FROM change_request_entry WHERE request_id=?",
                UUID.class,
                request))
        .isEqualTo(originalDocument);
    assertThat(
            jdbc.queryForObject(
                "SELECT current_document_id FROM care_item WHERE id=?", UUID.class, item))
        .isEqualTo(originalDocument);
    assertThat(
            jdbc.queryForObject(
                "SELECT body::text FROM content_document WHERE id=?",
                String.class,
                originalDocument))
        .isEqualTo(originalBody);
    assertThat(count("care_item_basis", "item_id", item)).isEqualTo(2);
    assertThat(count("care_item_history", "item_id", item)).isEqualTo(2);
    assertThat(postData(owner, "/request-applications", approval, 200)).isEqualTo(applied);

    erase(source);
    var afterErasure = detail(member, request).at("/entries/0");
    assertThat(afterErasure.path("status").asText()).isEqualTo("APPLIED");
    assertThat(afterErasure.at("/decision/appliedValues").isNull()).isTrue();
    assertThat(afterErasure.path("redactedFields"))
        .doesNotContain(mapper.valueToTree("decision.appliedValues"));
    assertThat(postData(owner, "/request-applications", approval, 200)).isEqualTo(applied);
  }

  @Test
  void reusedClientIdWithDifferentLogicalContentAndTamperedReviewAreRejected() throws Exception {
    UUID proposal = createProposal();
    var input = batch();
    input
        .withArray("entries")
        .add(memberEntry(member, proposal, null, createValues("SCHEDULE", "요청 원문")));
    UUID request = uuid(postData(member, "/change-requests", input, 201), "requestId");
    var altered = input.deepCopy();
    ((ObjectNode) altered.at("/entries/0/values")).put("title", "다른 내용");
    postError(member, "/change-requests", altered, 409, "IDEMPOTENCY_KEY_REUSED");
    var approval = approval(request, 0, null);
    ((ObjectNode) approval.at("/entries/0")).put("reviewToken", "0".repeat(64));
    postError(owner, "/request-applications", approval, 409, "CHANGE_REQUEST_STATE_CONFLICT");
    assertThat(count("care_item", "space_id", space)).isZero();
    assertThat(detail(owner, request).at("/entries/0/status").asText()).isEqualTo("PENDING");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 21})
  void submissionRejectsOutOfRangeBatchBeforePersistence(int size) throws Exception {
    var input = batch();
    for (int i = 0; i < size; i++) input.withArray("entries").add(mapper.createObjectNode());
    postError(member, "/change-requests", input, 400, "INVALID_REQUEST");
    assertThat(count("change_request", "space_id", space)).isZero();
  }

  @Test
  void ownerDirectApplicationInvalidatesPendingMemberRequestsAndRequestApprovalCannotRepeatIt()
      throws Exception {
    UUID proposal = createProposal();
    UUID request = submitCreate(member, proposal);
    var pendingApproval = approval(request, 0, null);
    var directInput = batch();
    directInput.withArray("entries").add(entry(proposal, null, createValues("SCHEDULE", "직접 확정")));
    decisions.apply(owner, space, directInput);
    postError(
        owner, "/request-applications", pendingApproval, 409, "CHANGE_REQUEST_STATE_CONFLICT");
    assertThat(detail(member, request).at("/entries/0/status").asText()).isEqualTo("INVALIDATED");
    assertThat(count("care_item", "space_id", space)).isEqualTo(1);
  }

  @Test
  void declinesAndCancellationsLeaveOriginalProposalOpenAndAllowFreshRequests() throws Exception {
    UUID proposal = createProposal();
    UUID request = submitCreate(member, proposal);
    var decline = batch();
    decline.withArray("entries").add(decisionEntry(detail(owner, request).at("/entries/0")));
    var declined = postData(owner, "/request-declines", decline, 200);
    assertThat(declined.path("declinedRequestEntryIds").size()).isEqualTo(1);
    assertThat(postData(owner, "/request-declines", decline, 200)).isEqualTo(declined);
    assertThat(detail(member, request).at("/entries/0/status").asText()).isEqualTo("DECLINED");
    assertThat(queries.detail(member, space, proposal).status())
        .isEqualTo(ProposalStatus.REVIEW_PENDING);
    UUID next = submitCreate(member, proposal);
    var cancel = cancellation(detail(member, next).at("/entries/0"));
    var canceled = postData(member, "/change-requests/" + next + "/cancellations", cancel, 200);
    assertThat(canceled.path("canceledRequestEntryIds").size()).isEqualTo(1);
    assertThat(postData(member, "/change-requests/" + next + "/cancellations", cancel, 200))
        .isEqualTo(canceled);
    assertThat(detail(owner, next).at("/entries/0/status").asText()).isEqualTo("CANCELED");
    assertThat(queries.detail(member, space, proposal).status())
        .isEqualTo(ProposalStatus.REVIEW_PENDING);
    assertThat(submitCreate(member, proposal)).isNotEqualTo(next);
    assertThat(count("care_item", "space_id", space)).isZero();
  }

  @Test
  void enforcesMembershipOwnerReviewAndOriginalSubmitterCancellation() throws Exception {
    UUID proposal = createProposal();
    var ownerSubmission = batch();
    ownerSubmission
        .withArray("entries")
        .add(entry(proposal, null, createValues("SCHEDULE", "권한 검사")));
    postError(owner, "/change-requests", ownerSubmission, 403, "FORBIDDEN");
    UUID request = submitCreate(member, proposal);
    var apply = approval(request, 0, null);
    postError(member, "/request-applications", apply, 403, "FORBIDDEN");
    var cancel = cancellation(detail(member, request).at("/entries/0"));
    postError(owner, "/change-requests/" + request + "/cancellations", cancel, 403, "FORBIDDEN");
    UUID stranger = user();
    mvc.perform(get(base() + "/change-requests/" + request).with(authentication(login(stranger))))
        .andExpect(status().isForbidden());
    mvc.perform(get(base() + "/change-requests/" + request)).andExpect(status().isUnauthorized());
    mvc.perform(
            post(base() + "/request-applications")
                .with(authentication(login(owner)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(apply.toString()))
        .andExpect(status().isForbidden());
    UUID other = user();
    join(other);
    assertThat(detail(other, request).path("requestId").asText()).isEqualTo(request.toString());
    assertThat(detail(other, request).at("/entries/0/reviewToken").isNull()).isTrue();
  }

  @Test
  void selectedEntriesAreAtomicWhileUnselectedEntriesRemainPending() throws Exception {
    var source =
        propose(
            List.of(
                new Spec("CREATE", "SCHEDULE", null, Map.of("title", "일정"), List.of()),
                new Spec("CREATE", "TASK", null, Map.of("title", "할일"), List.of())));
    var ids = ids(source);
    var input = batch();
    input
        .withArray("entries")
        .add(memberEntry(member, ids.get(0), null, createValues("SCHEDULE", "일정 요청")));
    input
        .withArray("entries")
        .add(memberEntry(member, ids.get(1), null, createValues("TASK", "할일 요청")));
    UUID request = uuid(postData(member, "/change-requests", input, 201), "requestId");
    var d = detail(owner, request);
    var both = batch();
    both.withArray("entries").add(decisionEntry(d.at("/entries/0")));
    var invalid = decisionEntry(d.at("/entries/1"));
    invalid.set(
        "values",
        createValues("TASK", "잘못된 요청")
            .set("fields", mapper.readTree("{\"dueDate\":\"2026-02-30\"}")));
    both.withArray("entries").add(invalid);
    postError(owner, "/request-applications", both, 400, "INVALID_REQUEST");
    assertThat(count("care_item", "space_id", space)).isZero();
    assertThat(detail(owner, request).at("/counts/pending").asInt()).isEqualTo(2);
    postData(owner, "/request-applications", approval(request, 0, null), 200);
    var after = detail(owner, request);
    assertThat(after.at("/entries/0/status").asText()).isEqualTo("APPLIED");
    assertThat(after.at("/entries/1/status").asText()).isEqualTo("PENDING");
    assertThat(after.at("/counts/applied").asInt()).isEqualTo(1);
    assertThat(after.at("/counts/pending").asInt()).isEqualTo(1);
  }

  @Test
  void requestSubmissionRollsBackWholeBatchAndKeepsMemberLeaseOnInvalidLastEntry()
      throws Exception {
    UUID item = seedItem("TASK");
    var ids =
        ids(
            propose(
                List.of(
                    update(item, "TASK"),
                    new Spec("CREATE", "SCHEDULE", null, Map.of("title", "일정"), List.of()))));
    var input = batch();
    input
        .withArray("entries")
        .add(memberEntry(member, ids.get(0), item, mapper.readTree("{\"title\":\"수정\"}")));
    input
        .withArray("entries")
        .add(memberEntry(member, ids.get(1), null, createValues("SCHEDULE", "")));
    postError(member, "/change-requests", input, 400, "INVALID_REQUEST");
    assertThat(count("change_request", "space_id", space)).isZero();
    assertThat(itemQueries.detail(member, space, item).editLock()).isNotNull();
    assertThat(itemQueries.detail(member, space, item).version()).isEqualTo("1");
  }

  @Test
  void staleTargetCannotBeSilentlyRebasedBySupplyingNewVersion() throws Exception {
    UUID item = seedItem("TASK");
    UUID proposal = ids(propose(List.of(update(item, "TASK")))).getFirst();
    var input = batch();
    input
        .withArray("entries")
        .add(memberEntry(member, proposal, item, mapper.readTree("{\"title\":\"오래된 수정안\"}")));
    UUID request = uuid(postData(member, "/change-requests", input, 201), "requestId");
    var approval = approval(request, 0, item);
    jdbc.update("UPDATE care_item SET version=version+1 WHERE id=?", item);
    ((ObjectNode) approval.withArray("entries").get(0)).put("expectedItemVersion", "2");
    postStatus(owner, "/request-applications", approval, 409);
    assertThat(detail(owner, request).at("/entries/0/status").asText()).isEqualTo("INVALIDATED");
    assertThat(itemQueries.detail(owner, space, item).title()).isEqualTo("기존 제목");
  }

  @Test
  void endedRequesterCannotBeApprovedOrRevivedByRejoining() throws Exception {
    UUID proposal = createProposal();
    UUID request = submitCreate(member, proposal);
    var approval = approval(request, 0, null);
    String version =
        jdbc.queryForObject("SELECT version::text FROM care_space WHERE id=?", String.class, space);
    membershipChanges.remove(
        owner, space, memberMembership, new MembershipChangeRequest(version, UUID.randomUUID()));
    postError(owner, "/request-applications", approval, 409, "CHANGE_REQUEST_STATE_CONFLICT");
    join(member);
    assertThat(detail(member, request).at("/entries/0/status").asText()).isEqualTo("INVALIDATED");
    postError(owner, "/request-applications", approval, 409, "CHANGE_REQUEST_STATE_CONFLICT");
    assertThat(submitCreate(member, proposal)).isNotEqualTo(request);
  }

  @Test
  void ownershipTransferChangesReviewerAndInvalidatesFormerOwnersReviewToken() throws Exception {
    UUID nextOwner = user();
    UUID nextMembership = join(nextOwner);
    UUID request = submitCreate(member, createProposal());
    var previousApproval = approval(request, 0, null);
    String version =
        jdbc.queryForObject("SELECT version::text FROM care_space WHERE id=?", String.class, space);
    membershipChanges.transfer(
        owner, space, new TransferOwnershipRequest(nextMembership, version, UUID.randomUUID()));
    postError(owner, "/request-applications", previousApproval, 403, "FORBIDDEN");
    postError(
        nextOwner, "/request-applications", previousApproval, 409, "CHANGE_REQUEST_STATE_CONFLICT");
    var fresh = batch();
    fresh.withArray("entries").add(decisionEntry(detail(nextOwner, request).at("/entries/0")));
    postData(nextOwner, "/request-applications", fresh, 200);
    assertThat(detail(member, request).at("/entries/0/decision/decidedByMembershipId").asText())
        .isEqualTo(nextMembership.toString());
  }

  @Test
  void originalSourceErasureRemovesStoredRequestAndAppliedDocumentsButRetainsDecisionRows()
      throws Exception {
    var source =
        propose(List.of(new Spec("CREATE", "SCHEDULE", null, Map.of("title", "민감 원안"), List.of())));
    UUID request = submitCreate(member, ids(source).getFirst());
    var approval = approval(request, 0, null);
    var applied = postData(owner, "/request-applications", approval, 200);
    UUID item = uuid(applied.at("/results/0"), "itemId");
    var entriesBefore =
        jdbc.queryForList(
            "SELECT id,status,decision_batch_id,result_item_id,result_item_version FROM change_request_entry WHERE request_id=? ORDER BY ordinal_no",
            request);
    UUID document =
        jdbc.queryForObject(
            "SELECT request_document_id FROM change_request_entry WHERE request_id=?",
            UUID.class,
            request);
    erase(source);
    assertThat(
            jdbc.queryForList(
                "SELECT id,status,decision_batch_id,result_item_id,result_item_version FROM change_request_entry WHERE request_id=? ORDER BY ordinal_no",
                request))
        .containsExactlyElementsOf(entriesBefore);
    var erased =
        jdbc.queryForMap("SELECT body,redacted_at FROM content_document WHERE id=?", document);
    assertThat(erased.get("body")).isNull();
    assertThat(erased.get("redacted_at")).isNotNull();
    var after = detail(owner, request).at("/entries/0");
    assertThat(after.path("status").asText()).isEqualTo("APPLIED");
    assertThat(after.path("requestedValues").isNull()).isTrue();
    assertThat(after.at("/decision/appliedValues").isNull()).isTrue();
    assertThat(postData(owner, "/request-applications", approval, 200)).isEqualTo(applied);
    assertThat(count("care_item_history", "item_id", item)).isEqualTo(1);
  }

  @Test
  void listPagesRemainScopedToSpaceAndStatusAndExposeEntryCounts() throws Exception {
    var ids =
        ids(
            propose(
                List.of(
                    new Spec("CREATE", "SCHEDULE", null, Map.of("title", "첫째"), List.of()),
                    new Spec("CREATE", "SCHEDULE", null, Map.of("title", "둘째"), List.of()),
                    new Spec("CREATE", "SCHEDULE", null, Map.of("title", "셋째"), List.of()))));
    var requests = new HashSet<String>();
    for (UUID id : ids) requests.add(submitCreate(member, id).toString());
    JsonNode first = getData(member, "/change-requests?size=2");
    assertThat(first.path("items").size()).isEqualTo(2);
    assertThat(first.path("hasNext").asBoolean()).isTrue();
    String cursor = first.path("nextCursor").asText();
    JsonNode second = getData(owner, "/change-requests?size=2&cursor=" + cursor);
    assertThat(second.path("items").size()).isEqualTo(1);
    assertThat(second.path("hasNext").asBoolean()).isFalse();
    var actual = new HashSet<String>();
    first.path("items").forEach(n -> actual.add(n.path("requestId").asText()));
    second.path("items").forEach(n -> actual.add(n.path("requestId").asText()));
    assertThat(actual).isEqualTo(requests);
    mvc.perform(
            get(base() + "/change-requests")
                .with(authentication(login(owner)))
                .param("status", "APPLIED")
                .param("cursor", cursor))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get(base() + "/change-requests").with(authentication(login(owner))).param("size", "51"))
        .andExpect(status().isBadRequest());
    assertThat(first.at("/items/0/counts/pending").asInt()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "change-requests",
        "request-applications",
        "request-declines",
        "change-requests/00000000-0000-4000-8000-000000000001/cancellations"
      })
  void everyMutationRejectsOversizedRawBodyBeforeParsing(String endpoint) throws Exception {
    mvc.perform(
            post(base() + "/" + endpoint)
                .with(authentication(login(member)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("[" + " ".repeat(CareMutationSizeLimits.REQUEST_BYTES)))
        .andExpect(status().is(413))
        .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    assertThat(count("change_request", "space_id", space)).isZero();
  }

  @Test
  void utf16AndSubmissionDocumentBudgetApplyWithoutTruncationOrPartialPersistence()
      throws Exception {
    var specs =
        java.util.stream.IntStream.range(0, 6)
            .mapToObj(
                i -> new Spec("CREATE", "SCHEDULE", null, Map.of("title", "AI"), List.<UUID>of()))
            .toList();
    var ids = ids(propose(specs));
    var input = batch();
    String boundary = "😀".repeat(5000);
    var values = createValues("SCHEDULE", boundary + "x");
    input.withArray("entries").add(memberEntry(member, ids.getFirst(), null, values));
    postError(member, "/change-requests", input, 400, "INVALID_REQUEST");
    values.put("title", boundary);
    UUID request = uuid(postData(member, "/change-requests", input, 201), "requestId");
    assertThat(detail(owner, request).at("/entries/0/requestedValues/title").asText())
        .isEqualTo(boundary);
    postData(
        member,
        "/change-requests/" + request + "/cancellations",
        cancellation(detail(member, request).at("/entries/0")),
        200);
    var oversized = batch();
    String text = "가".repeat(10000);
    for (UUID id : ids) {
      var v = createValues("SCHEDULE", text).put("description", text);
      v.withObject("fields").put("location", text);
      oversized.withArray("entries").add(memberEntry(member, id, null, v));
    }
    assertThat(mapper.writeValueAsBytes(oversized).length)
        .isLessThan(CareMutationSizeLimits.REQUEST_BYTES);
    postError(member, "/change-requests", oversized, 400, "INVALID_REQUEST");
    assertThat(count("change_request", "space_id", space)).isEqualTo(1);
    assertThat(count("care_item", "space_id", space)).isZero();
  }

  @Test
  void ownerOverrideFinalDocumentBudgetRejectsWholeSelection() throws Exception {
    var specs =
        java.util.stream.IntStream.range(0, 6)
            .mapToObj(
                i -> new Spec("CREATE", "SCHEDULE", null, Map.of("title", "AI"), List.<UUID>of()))
            .toList();
    var input = batch();
    for (UUID id : ids(propose(specs)))
      input
          .withArray("entries")
          .add(memberEntry(member, id, null, createValues("SCHEDULE", "정상 요청")));
    UUID request = uuid(postData(member, "/change-requests", input, 201), "requestId");
    var body = batch();
    String text = "가".repeat(10000);
    for (JsonNode e : detail(owner, request).path("entries")) {
      var choice = decisionEntry(e);
      var v = createValues("SCHEDULE", text).put("description", text);
      v.withObject("fields").put("location", text);
      choice.set("values", v);
      body.withArray("entries").add(choice);
    }
    postError(owner, "/request-applications", body, 400, "INVALID_REQUEST");
    assertThat(count("care_item", "space_id", space)).isZero();
    assertThat(detail(owner, request).at("/counts/pending").asInt()).isEqualTo(6);
  }

  @Test
  void simultaneousDirectAndRequestApplicationCreateExactlyOneItem() throws Exception {
    UUID proposal = createProposal();
    UUID request = submitCreate(member, proposal);
    var approval = approval(request, 0, null);
    var directBody = batch();
    directBody.withArray("entries").add(entry(proposal, null, createValues("SCHEDULE", "직접")));
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Integer> memberDecision =
          () -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return mvc.perform(
                    post(base() + "/request-applications")
                        .with(authentication(login(owner)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approval.toString()))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      Callable<Integer> directDecision =
          () -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return mvc.perform(
                    post(base() + "/proposal-applications")
                        .with(authentication(login(owner)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(directBody.toString()))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      var first = pool.submit(memberDecision);
      var second = pool.submit(directDecision);
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
    }
    assertThat(count("care_item", "space_id", space)).isEqualTo(1);
    assertThat(count("proposal_decision", "space_id", space)).isEqualTo(1);
  }

  @Test
  void simultaneousApprovalsOfDifferentMembersRequestsHaveExactlyOneWinner() throws Exception {
    UUID other = user();
    join(other);
    UUID proposal = createProposal();
    UUID firstRequest = submitCreate(member, proposal),
        secondRequest = submitCreate(other, proposal);
    var firstBody = approval(firstRequest, 0, null);
    var secondBody = approval(secondRequest, 0, null);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = new ArrayList<Future<Integer>>();
      for (JsonNode body : List.of(firstBody, secondBody))
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await(10, TimeUnit.SECONDS);
                  return mvc.perform(
                          post(base() + "/request-applications")
                              .with(authentication(login(owner)))
                              .with(csrf())
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(body.toString()))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              List.of(
                  futures.get(0).get(20, TimeUnit.SECONDS),
                  futures.get(1).get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
    }
    assertThat(
            List.of(
                detail(owner, firstRequest).at("/entries/0/status").asText(),
                detail(owner, secondRequest).at("/entries/0/status").asText()))
        .containsExactlyInAnyOrder("APPLIED", "INVALIDATED");
    assertThat(count("care_item", "space_id", space)).isEqualTo(1);
    assertThat(count("change_request_decision_batch", "space_id", space)).isEqualTo(1);
  }

  @Test
  void cancelAndDeclineSelectionsAreAtomicWhenOneEntryWasAlreadyDecided() throws Exception {
    var ids =
        ids(
            propose(
                List.of(
                    new Spec("CREATE", "SCHEDULE", null, Map.of("title", "첫"), List.of()),
                    new Spec("CREATE", "SCHEDULE", null, Map.of("title", "둘"), List.of()))));
    var input = batch();
    for (UUID id : ids)
      input.withArray("entries").add(memberEntry(member, id, null, createValues("SCHEDULE", "요청")));
    UUID request = uuid(postData(member, "/change-requests", input, 201), "requestId");
    var before = detail(owner, request);
    var firstCancel = cancellation(before.at("/entries/0"));
    postData(member, "/change-requests/" + request + "/cancellations", firstCancel, 200);
    var bothCancel = cancellation(before.at("/entries/1"));
    bothCancel
        .withArray("entries")
        .add(
            mapper
                .createObjectNode()
                .put("requestEntryId", before.at("/entries/0/requestEntryId").asText()));
    postError(
        member,
        "/change-requests/" + request + "/cancellations",
        bothCancel,
        409,
        "CHANGE_REQUEST_STATE_CONFLICT");
    var bothDecline = batch();
    bothDecline
        .withArray("entries")
        .add(decisionEntry(before.at("/entries/1")))
        .add(decisionEntry(before.at("/entries/0")));
    postError(owner, "/request-declines", bothDecline, 409, "CHANGE_REQUEST_STATE_CONFLICT");
    var after = detail(owner, request);
    assertThat(after.at("/entries/0/status").asText()).isEqualTo("CANCELED");
    assertThat(after.at("/entries/1/status").asText()).isEqualTo("PENDING");
    assertThat(count("change_request_decision_batch", "space_id", space)).isEqualTo(1);
    assertThat(count("care_item", "space_id", space)).isZero();
  }

  @Test
  void pendingSourceErasureRedactsActualRequestBodyAndReplayContainsOnlySubmissionMetadata()
      throws Exception {
    var source =
        propose(List.of(new Spec("CREATE", "SCHEDULE", null, Map.of("title", "AI 원안"), List.of())));
    var input = batch();
    input
        .withArray("entries")
        .add(
            memberEntry(
                member, ids(source).getFirst(), null, createValues("SCHEDULE", "지워야 할 합성 내용")));
    var submitted = postData(member, "/change-requests", input, 201);
    UUID request = uuid(submitted, "requestId");
    var pendingApproval = approval(request, 0, null);
    UUID document =
        jdbc.queryForObject(
            "SELECT request_document_id FROM change_request_entry WHERE request_id=?",
            UUID.class,
            request);
    erase(source);
    var stored =
        jdbc.queryForMap("SELECT body,redacted_at FROM content_document WHERE id=?", document);
    assertThat(stored.get("body")).isNull();
    assertThat(stored.get("redacted_at")).isNotNull();
    var entry = detail(owner, request).at("/entries/0");
    assertThat(entry.path("status").asText()).isEqualTo("INVALIDATED");
    assertThat(entry.path("requestedValues").isNull()).isTrue();
    assertThat(entry.path("reviewToken").isNull()).isTrue();
    assertThat(entry.path("evidenceIds").isEmpty()).isTrue();
    assertThat(entry.toString()).doesNotContain("지워야 할 합성 내용");
    assertThat(postData(member, "/change-requests", input, 201)).isEqualTo(submitted);
    postError(
        owner, "/request-applications", pendingApproval, 409, "CHANGE_REQUEST_STATE_CONFLICT");
    assertThat(count("care_item", "space_id", space)).isZero();
  }

  @Test
  void deletedTargetIsVisibleAsInvalidRequestMetadataWithoutCurrentContent() throws Exception {
    UUID item = seedItem("TASK");
    UUID proposal = ids(propose(List.of(update(item, "TASK")))).getFirst();
    var input = batch();
    input
        .withArray("entries")
        .add(memberEntry(member, proposal, item, mapper.readTree("{\"title\":\"수정 요청\"}")));
    UUID request = uuid(postData(member, "/change-requests", input, 201), "requestId");
    jdbc.update("UPDATE care_item SET visibility='DELETED',version=version+1 WHERE id=?", item);
    var entry = detail(owner, request).at("/entries/0");
    assertThat(entry.path("status").asText()).isEqualTo("INVALIDATED");
    assertThat(entry.path("currentValues").isNull()).isTrue();
    assertThat(entry.path("currentItemVersion").asText()).isEqualTo("2");
  }

  @Test
  void exactSubmissionDocumentByteBudgetIsAcceptedAndOneAdditionalByteIsRejected()
      throws Exception {
    var specs =
        java.util.stream.IntStream.range(0, 18)
            .mapToObj(
                i -> new Spec("CREATE", "GUIDANCE", null, Map.of("title", "AI"), List.<UUID>of()))
            .toList();
    var ids = ids(propose(specs));
    var values = new ArrayList<ObjectNode>();
    for (int i = 0; i < 17; i++) {
      var v = createValues("GUIDANCE", "a".repeat(10000)).put("description", "b".repeat(10000));
      v.withObject("fields").put("sourceName", "c".repeat(10000));
      values.add(v);
    }
    var last = createValues("GUIDANCE", "마지막").put("description", "");
    last.withObject("fields").put("sourceName", "");
    values.add(last);
    int baseBytes = 0;
    for (var v : values)
      baseBytes += mapper.writeValueAsBytes(mapper.createObjectNode().set("values", v)).length;
    int remaining = CareMutationSizeLimits.DOCUMENT_BYTES - baseBytes;
    assertThat(remaining).isBetween(10001, 20000);
    last.put("description", "d".repeat(10000));
    last.withObject("fields").put("sourceName", "e".repeat(remaining - 10000));
    int exactBytes = 0;
    for (var v : values)
      exactBytes += mapper.writeValueAsBytes(mapper.createObjectNode().set("values", v)).length;
    assertThat(exactBytes).isEqualTo(CareMutationSizeLimits.DOCUMENT_BYTES);
    var input = batch();
    for (int i = 0; i < ids.size(); i++)
      input.withArray("entries").add(memberEntry(member, ids.get(i), null, values.get(i)));
    last.withObject("fields").put("sourceName", "e".repeat(remaining - 10000) + "x");
    postError(member, "/change-requests", input, 400, "INVALID_REQUEST");
    assertThat(count("change_request", "space_id", space)).isZero();
    last.withObject("fields").put("sourceName", "e".repeat(remaining - 10000));
    UUID request = uuid(postData(member, "/change-requests", input, 201), "requestId");
    assertThat(detail(owner, request).path("entries").size()).isEqualTo(18);
    assertThat(detail(owner, request).at("/entries/17/requestedValues/fields/sourceName").asText())
        .hasSize(remaining - 10000);
  }

  @Nested
  class DirectInput {
    @Autowired DirectCareItemUpdateService updates;

    @Test
    void concurrentSavesWithOneLeaseProduceOneVersionAndOneHistoryEntry() throws Exception {
      UUID item = uuid(postData(member, "/items", create("TASK", memberMembership), 201), "itemId");
      var first = change(member, item, 1).put("status", "IN_PROGRESS");
      var second =
          first
              .deepCopy()
              .put("clientRequestId", UUID.randomUUID().toString())
              .put("status", "COMPLETED");
      var start = new CountDownLatch(1);
      try (var pool = Executors.newFixedThreadPool(2)) {
        List<Future<String>> results = new ArrayList<>();
        for (var command : List.of(first, second))
          results.add(
              pool.submit(
                  () -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                      return updates.update(member, space, item, command, true).version();
                    } catch (ApiException e) {
                      return e.code().code();
                    }
                  }));
        start.countDown();
        assertThat(
                List.of(
                    results.get(0).get(10, TimeUnit.SECONDS),
                    results.get(1).get(10, TimeUnit.SECONDS)))
            .containsExactlyInAnyOrder("2", "VERSION_CONFLICT");
      }
      assertThat(count("care_item_history", "item_id", item)).isEqualTo(2);
    }

    @Test
    void selfTaskErrorsNeverCreateAnItemOrCommandReceipt() throws Exception {
      var tooLong = create("TASK", memberMembership).put("title", "a".repeat(10001));
      postError(member, "/items", tooLong, 400, "INVALID_REQUEST");
      postError(
          member, "/items", tooLong.put("title", "a".repeat(1048576)), 413, "PAYLOAD_TOO_LARGE");
      assertThat(count("care_item", "space_id", space)).isZero();
    }

    private ObjectNode create(String type, UUID assignee) {
      var input = createValues(type, "직접 입력");
      if (assignee != null)
        input.withObject("fields").put("assigneeMembershipId", assignee.toString());
      return input.put("clientRequestId", UUID.randomUUID().toString());
    }

    private ObjectNode change(UUID actor, UUID item, long version) {
      return mapper
          .createObjectNode()
          .put("clientRequestId", UUID.randomUUID().toString())
          .put("expectedItemVersion", Long.toString(version))
          .put("leaseToken", locks.acquire(actor, space, item, version).leaseToken());
    }

    private JsonNode patchItem(
        UUID actor, UUID item, String suffix, ObjectNode input, int status, String code)
        throws Exception {
      var response =
          mvc.perform(
                  patch(base() + "/items/" + item + suffix)
                      .with(authentication(login(actor)))
                      .with(csrf())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(input.toString()))
              .andExpect(status().is(status))
              .andExpect(jsonPath("$.code").value(code))
              .andReturn()
              .getResponse();
      return mapper.readTree(response.getContentAsString()).path("data");
    }

    private ObjectNode submission(String action, JsonNode values) {
      var input = batch();
      input
          .withArray("entries")
          .addObject()
          .put("sourceType", "DIRECT_INPUT")
          .put("action", action)
          .set("values", values);
      return input;
    }

    @Test
    void memberImmediatelyCreatesOnlyTheirOwnTaskAndMayOnlyChangeItsStatus() throws Exception {
      var input = create("TASK", memberMembership);
      var first = postData(member, "/items", input, 201);
      UUID item = uuid(first, "itemId");
      assertThat(postData(member, "/items", input, 201)).isEqualTo(first);
      assertThat(getData(member, "/items/" + item).path("status").asText())
          .isEqualTo("NOT_STARTED");
      assertThat(count("change_request", "space_id", space)).isZero();
      postError(member, "/items", create("TASK", null), 403, "FORBIDDEN");
      postError(member, "/items", create("TASK", ownerMembership), 403, "FORBIDDEN");
      postError(member, "/items", create("QUESTION", null), 403, "FORBIDDEN");
      postError(owner, "/items", create("TASK", UUID.randomUUID()), 400, "INVALID_REQUEST");
      var status = change(member, item, 1).put("status", "COMPLETED");
      patchItem(
          member, item, "/status", status.deepCopy().put("title", "권한 우회"), 400, "INVALID_REQUEST");
      var content = status.deepCopy();
      content.remove("status");
      content.putObject("values").put("title", "권한 우회");
      patchItem(member, item, "", content, 403, "FORBIDDEN");
      var completion = patchItem(member, item, "/status", status, 200, "SUCCESS");
      assertThat(patchItem(member, item, "/status", status, 200, "SUCCESS")).isEqualTo(completion);
      var next = change(member, item, 2).put("status", "NOT_STARTED");
      patchItem(member, item, "/status", next, 409, "ITEM_STATE_CONFLICT");
      patchItem(member, item, "/status", next.put("status", "IN_PROGRESS"), 200, "SUCCESS");
      var update = change(owner, item, 3);
      update.putObject("values").putObject("fields").putNull("assigneeMembershipId");
      patchItem(owner, item, "", update, 200, "SUCCESS");
      patchItem(member, item, "/status", status, 403, "FORBIDDEN");
      assertThat(itemQueries.detail(owner, space, item).version()).isEqualTo("4");
    }

    @Test
    void memberAndOwnerCanFollowUnassignedTaskRequestWithoutAnAiProposal() throws Exception {
      var body = submission("CREATE_ITEM", createValues("TASK", "구성원이 제출한 값"));
      UUID request = uuid(postData(member, "/change-requests", body, 201), "requestId");
      assertThat(count("care_item", "space_id", space)).isZero();
      var pending = detail(owner, request).at("/entries/0");
      assertThat(pending.path("sourceType").asText()).isEqualTo("DIRECT_INPUT");
      assertThat(pending.path("proposalId").isNull()).isTrue();
      assertThat(pending.path("status").asText()).isEqualTo("PENDING");
      assertThat(getData(member, "/change-requests").at("/items/0/entries/0/sourceType").asText())
          .isEqualTo("DIRECT_INPUT");
      var approve = approval(request, 0, null);
      var entry = (ObjectNode) approve.at("/entries/0");
      entry.set("values", createValues("QUESTION", "유형 바꾸기"));
      postError(owner, "/request-applications", approve, 400, "INVALID_REQUEST");
      entry.set("values", createValues("TASK", "소유자 확정값"));
      var applied = postData(owner, "/request-applications", approve, 200);
      UUID item = uuid(applied.at("/results/0"), "itemId");
      assertThat(postData(owner, "/request-applications", approve, 200)).isEqualTo(applied);
      assertThat(getData(member, "/items/" + item).path("title").asText()).isEqualTo("소유자 확정값");
      var result = detail(member, request).at("/entries/0");
      assertThat(result.at("/requestedValues/title").asText()).isEqualTo("구성원이 제출한 값");
      assertThat(result.at("/decision/appliedValues/title").asText()).isEqualTo("소유자 확정값");
      assertThat(count("proposal_decision_batch", "space_id", space)).isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT submitted_by_membership_id FROM care_item_basis WHERE item_id=?",
                  UUID.class,
                  item))
          .isEqualTo(memberMembership);
      UUID source =
          jdbc.queryForObject(
              "SELECT direct_document_id FROM care_item_basis WHERE item_id=?", UUID.class, item);
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              tx -> {
                jdbc.update(
                    "UPDATE content_document SET body=NULL,redacted_at=now() WHERE id=?", source);
                execution.invalidateDirectDocument(space, source);
              });
      var redacted = detail(member, request).at("/entries/0");
      assertThat(redacted.path("requestedValues").isNull()).isTrue();
      assertThat(redacted.at("/decision/appliedValues").isNull()).isTrue();
      assertThat(itemQueries.detail(member, space, item).title()).isNull();
    }

    @Test
    void pendingDirectUpdateIsUniqueUntilDecisionOrVersionChange() throws Exception {
      var item = uuid(postData(owner, "/items", create("QUESTION", null), 201), "itemId");
      var body = submission("UPDATE_ITEM", mapper.createObjectNode().put("title", "질문 수정"));
      var entry = (ObjectNode) body.at("/entries/0");
      entry
          .put("targetItemId", item.toString())
          .put("expectedItemVersion", "1")
          .put("leaseToken", locks.acquire(member, space, item, 1).leaseToken());
      UUID request = uuid(postData(member, "/change-requests", body, 201), "requestId");
      var duplicate = body.deepCopy().put("clientRequestId", UUID.randomUUID().toString());
      String memberLease = locks.acquire(member, space, item, 1).leaseToken();
      ((ObjectNode) duplicate.at("/entries/0")).put("leaseToken", memberLease);
      postError(member, "/change-requests", duplicate, 409, "CHANGE_REQUEST_ALREADY_PENDING");
      locks.release(member, space, item, memberLease);
      var ownerUpdate = change(owner, item, 1);
      ownerUpdate.putObject("values").put("title", "새 질문");
      var oldApproval = decisionEntry(detail(owner, request).at("/entries/0"));
      patchItem(owner, item, "", ownerUpdate, 200, "SUCCESS");
      assertThat(detail(owner, request).at("/entries/0/invalidReason").asText())
          .isEqualTo("TARGET_VERSION_CHANGED");
      var decision = batch();
      decision.withArray("entries").add(oldApproval);
      postError(owner, "/request-applications", decision, 409, "VERSION_CONFLICT");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void leavingOrRemovalUnassignsOnlyUnfinishedTasksAndKeepsHistory(boolean leave)
        throws Exception {
      UUID work = uuid(postData(member, "/items", create("TASK", memberMembership), 201), "itemId");
      UUID done = uuid(postData(member, "/items", create("TASK", memberMembership), 201), "itemId");
      patchItem(
          member,
          done,
          "/status",
          change(member, done, 1).put("status", "COMPLETED"),
          200,
          "SUCCESS");
      var held = change(owner, work, 1);
      held.putObject("values").put("title", "오래된 편집");
      var command = new MembershipChangeRequest("1", UUID.randomUUID());
      if (leave) membershipChanges.depart(member, space, command);
      else membershipChanges.remove(owner, space, memberMembership, command);
      assertThat(itemQueries.detail(owner, space, work).version()).isEqualTo("2");
      assertThat(itemQueries.detail(owner, space, work).editLock()).isNull();
      patchItem(owner, work, "", held, 409, "VERSION_CONFLICT");
      String assignmentSql =
          "SELECT d.body #>> '{fields,assigneeMembershipId}' FROM care_item i JOIN content_document d ON d.id=i.current_document_id WHERE i.id=?";
      assertThat(jdbc.queryForObject(assignmentSql, String.class, work)).isNull();
      assertThat(jdbc.queryForObject(assignmentSql, String.class, done))
          .isEqualTo(memberMembership.toString());
      assertThat(
              jdbc.queryForObject(
                  "SELECT changed_by_membership_id FROM care_item_history WHERE item_id=? AND operation='UNASSIGN'",
                  UUID.class,
                  work))
          .isEqualTo(leave ? memberMembership : ownerMembership);
      patchItem(
          owner,
          done,
          "/status",
          change(owner, done, 2).put("status", "IN_PROGRESS"),
          200,
          "SUCCESS");
      assertThat(jdbc.queryForObject(assignmentSql, String.class, done)).isNull();
      join(member);
      var replay =
          mapper
              .createObjectNode()
              .put("expectedItemVersion", "2")
              .put("clientRequestId", UUID.randomUUID().toString())
              .put("leaseToken", "old")
              .put("status", "IN_PROGRESS");
      patchItem(member, work, "/status", replay, 403, "FORBIDDEN");
    }

    @Test
    void directAndAiEntriesUseOneAtomicOwnerDecisionWithoutForgedProposalReferences()
        throws Exception {
      UUID proposal = createProposal();
      var input = submission("CREATE_ITEM", createValues("QUESTION", "직접 질문"));
      var injected = input.deepCopy();
      ((ObjectNode) injected.at("/entries/0")).put("proposalId", proposal.toString());
      postError(member, "/change-requests", injected, 400, "INVALID_REQUEST");
      input
          .withArray("entries")
          .add(memberEntry(member, proposal, null, createValues("SCHEDULE", "AI 일정")));
      UUID request = uuid(postData(member, "/change-requests", input, 201), "requestId");
      var details = detail(owner, request).path("entries");
      var choice = batch();
      choice
          .withArray("entries")
          .add(decisionEntry(details.get(0)))
          .add(decisionEntry(details.get(1)));
      var broken = choice.deepCopy();
      ((ObjectNode) broken.at("/entries/1")).put("reviewToken", "0".repeat(64));
      postError(owner, "/request-applications", broken, 409, "CHANGE_REQUEST_STATE_CONFLICT");
      assertThat(count("care_item", "space_id", space)).isZero();
      var result = postData(owner, "/request-applications", choice, 200);
      assertThat(result.path("results").size()).isEqualTo(2);
      assertThat(count("proposal_decision", "space_id", space)).isEqualTo(1);
    }
  }

  private UUID join(UUID actor) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO space_membership(id,space_id,user_id,joined_at) VALUES (?,?,?,now())",
        id,
        space,
        actor);
    return id;
  }

  private UUID createProposal() {
    return ids(propose(
            List.of(new Spec("CREATE", "SCHEDULE", null, Map.of("title", "AI 원안"), List.of()))))
        .getFirst();
  }

  private UUID submitCreate(UUID actor, UUID proposal) throws Exception {
    var body = batch();
    body.withArray("entries")
        .add(memberEntry(actor, proposal, null, createValues("SCHEDULE", "구성원 요청")));
    return uuid(postData(actor, "/change-requests", body, 201), "requestId");
  }

  private ObjectNode memberEntry(UUID actor, UUID proposal, UUID item, JsonNode values) {
    var p = queries.detail(actor, space, proposal);
    var e =
        mapper
            .createObjectNode()
            .put("proposalId", proposal.toString())
            .put("action", p.action().name())
            .put("reviewToken", p.reviewToken());
    if (item != null) {
      long version = Long.parseLong(itemQueries.detail(actor, space, item).version());
      e.put("targetItemId", item.toString())
          .put("expectedItemVersion", Long.toString(version))
          .put("leaseToken", locks.acquire(actor, space, item, version).leaseToken());
    }
    e.set("values", values == null ? mapper.nullNode() : values);
    return e;
  }

  private ObjectNode approval(UUID request, int index, UUID target) throws Exception {
    var body = batch();
    var entry = decisionEntry(detail(owner, request).path("entries").get(index));
    if (target != null) {
      long version = Long.parseLong(itemQueries.detail(owner, space, target).version());
      entry
          .put("expectedItemVersion", Long.toString(version))
          .put("leaseToken", locks.acquire(owner, space, target, version).leaseToken());
    }
    body.withArray("entries").add(entry);
    return body;
  }

  private ObjectNode decisionEntry(JsonNode entry) {
    return mapper
        .createObjectNode()
        .put("requestEntryId", entry.path("requestEntryId").asText())
        .put("reviewToken", entry.path("reviewToken").asText());
  }

  private ObjectNode cancellation(JsonNode entry) {
    var body = batch();
    body.withArray("entries")
        .add(
            mapper.createObjectNode().put("requestEntryId", entry.path("requestEntryId").asText()));
    return body;
  }

  private JsonNode detail(UUID actor, UUID request) throws Exception {
    return getData(actor, "/change-requests/" + request);
  }

  private JsonNode getData(UUID actor, String path) throws Exception {
    var result =
        mvc.perform(get(base() + path).with(authentication(login(actor))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString()).path("data");
  }

  private JsonNode postData(UUID actor, String path, JsonNode body, int expected) throws Exception {
    var result =
        mvc.perform(
                post(base() + path)
                    .with(authentication(login(actor)))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body.toString()))
            .andExpect(status().is(expected))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
    return mapper.readTree(result.getResponse().getContentAsString()).path("data");
  }

  private void postError(UUID actor, String path, JsonNode body, int expected, String code)
      throws Exception {
    mvc.perform(
            post(base() + path)
                .with(authentication(login(actor)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().is(expected))
        .andExpect(jsonPath("$.code").value(code));
  }

  private void postStatus(UUID actor, String path, JsonNode body, int expected) throws Exception {
    mvc.perform(
            post(base() + path)
                .with(authentication(login(actor)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().is(expected));
  }

  private UUID uuid(JsonNode node, String field) {
    return UUID.fromString(node.path(field).asText());
  }

  // Fixture helpers below use synthetic source records and in-process v2 responses only.
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

  private record Spec(
      String operation,
      String type,
      UUID target,
      Map<String, String> changes,
      List<UUID> candidates) {}
}
