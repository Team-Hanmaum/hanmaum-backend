package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hanmaum.backend.ai.dto.*;
import com.hanmaum.backend.analysis.service.AnalysisExecutionService;
import com.hanmaum.backend.careitem.dto.CreateCareItemRequest;
import com.hanmaum.backend.careitem.dto.ScheduleFields;
import com.hanmaum.backend.careitem.service.CareItemCreationService;
import com.hanmaum.backend.carespace.dto.*;
import com.hanmaum.backend.carespace.service.CareSpaceCreationService;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.security.oauth.MemberOAuth2User;
import com.hanmaum.backend.proposal.dto.*;
import com.hanmaum.backend.proposal.service.ProposalQueryService;
import com.hanmaum.backend.record.dto.*;
import com.hanmaum.backend.record.service.*;
import java.sql.Timestamp;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=4")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProposalQueryIntegrationTests {
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired CareSpaceCreationService spaces;
  @Autowired SourceRecordCreationService records;
  @Autowired AnalysisExecutionService execution;
  @Autowired ProposalQueryService queries;
  @Autowired CareItemCreationService careItems;
  @Autowired SourceRecordCursor sourceCursors;
  @Autowired PlatformTransactionManager transactions;
  UUID owner, member, outsider, membership;
  CreateCareSpaceResponse space;

  @BeforeEach
  void setup() {
    owner = user();
    member = user();
    outsider = user();
    space = spaces.create(owner, new CreateCareSpaceRequest("제안 테스트", UUID.randomUUID()));
    membership = join(member);
  }

  @Test
  void exposesTypedValuesAndEvidenceToOwnerAndCurrentMemberWithoutWrites() throws Exception {
    var c =
        complete(
            List.of(
                suggestion(
                    CareItemType.SCHEDULE,
                    Map.of(
                        "scheduledDate", "2026-10-20", "scheduledTime", "09:00", "location", "병원")),
                suggestion(
                    CareItemType.TASK,
                    Map.of("dueDate", "2026-10-21", "assigneeMembershipId", membership.toString())),
                suggestion(
                    CareItemType.QUESTION,
                    Map.of("description", "질문 내용", "confirmedAnswer", "확정 금지")),
                suggestion(
                    CareItemType.OBSERVATION,
                    Map.of("observedDate", "2026-10-19", "observedTimeText", "오후")),
                suggestion(
                    CareItemType.GUIDANCE,
                    Map.of(
                        "sourceName",
                        "보건소",
                        "receivedDate",
                        "2026-10-18",
                        "internalSecret",
                        "노출 금지"))));
    var before = snapshot();
    var items = list(owner).items();
    assertThat(items).hasSize(5);
    assertThat(list(member)).isEqualTo(list(owner));
    for (var p : items) {
      assertThat(p.recordId()).isEqualTo(c.recordId());
      assertThat(p.analysisId()).isEqualTo(c.analysis().analysisId());
      assertThat(p.action()).isEqualTo(ProposalAction.CREATE_ITEM);
      assertThat(p.status()).isEqualTo(ProposalStatus.REVIEW_PENDING);
      assertThat(p.needsConflictReview()).isNull();
      var detail = queries.detail(member, space.spaceId(), p.proposalId());
      assertThat(detail.targetItemId()).isNull();
      assertThat(detail.analysisTargetVersion()).isNull();
      assertThat(detail.currentItemVersion()).isNull();
      assertThat(detail.currentValues()).isNull();
      assertThat(detail.candidates()).isEmpty();
      assertThat(detail.relationKind()).isNull();
      assertThat(detail.reviewToken()).matches("[0-9a-f]{64}");
      assertThat(detail.redactedFields()).isEmpty();
      assertThat(detail.evidence())
          .singleElement()
          .satisfies(
              e -> {
                assertThat(e.quote()).isEqualTo("합성 원본 소식");
                assertThat(e.recordId()).isEqualTo(c.recordId());
                assertThat(e.valid()).isTrue();
                assertThat(e.author()).isEqualTo(new SourceRecordAuthor(membership, "가족", false));
                assertThat(e.recordedAt()).isEqualTo(c.recordedAt());
              });
      JsonNode body =
          mapper.readTree(
              mvc.perform(get(path() + "/" + p.proposalId()).with(authentication(login(owner))))
                  .andExpect(status().isOk())
                  .andExpect(header().string("Cache-Control", "no-store"))
                  .andExpect(jsonPath("$.success").value(true))
                  .andExpect(jsonPath("$.code").value("SUCCESS"))
                  .andExpect(jsonPath("$.errors").isEmpty())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      assertThat(body.size()).isEqualTo(6);
      assertThat(body.at("/data/proposedValues/title").asText()).isEqualTo("제안 " + p.itemType());
      assertThat(body.toString())
          .doesNotContain("internalSecret", "노출 금지", "confirmedAnswer", "changes");
      var v = body.at("/data/proposedValues");
      switch (p.itemType()) {
        case SCHEDULE -> {
          assertThat(v.path("scheduledDate").asText()).isEqualTo("2026-10-20");
          assertThat(v.path("scheduledTime").asText()).isEqualTo("09:00");
        }
        case TASK ->
            assertThat(v.path("assigneeMembershipId").asText()).isEqualTo(membership.toString());
        case QUESTION -> assertThat(v.size()).isEqualTo(2);
        case OBSERVATION -> assertThat(v.path("observedTimeText").asText()).isEqualTo("오후");
        case GUIDANCE -> assertThat(v.path("sourceName").asText()).isEqualTo("보건소");
      }
    }
    assertThat(snapshot()).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM care_item WHERE space_id = ?", Long.class, space.spaceId()))
        .isZero();
  }

  @Test
  void enforcesPermissionsForEmptyPagesAndDetailsAndAfterMembershipChanges() throws Exception {
    assertThat(list(owner).items()).isEmpty();
    assertCode(() -> list(outsider), CommonErrorCode.FORBIDDEN);
    complete(1);
    UUID id = list(owner).items().getFirst().proposalId();
    mvc.perform(get(path()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mvc.perform(get(path() + "/" + id).with(authentication(login(outsider))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    end();
    assertCode(() -> list(member), CommonErrorCode.FORBIDDEN);
    assertCode(() -> queries.detail(member, space.spaceId(), id), CommonErrorCode.FORBIDDEN);
    // Leaving does not erase another viewer's source evidence or invalidate a completed run.
    assertThat(queries.detail(owner, space.spaceId(), id).evidence()).hasSize(1);
    join(member);
    assertThat(list(member).items()).hasSize(1);
    var other = spaces.create(owner, new CreateCareSpaceRequest("다른 공간", UUID.randomUUID()));
    assertCode(
        () -> queries.detail(owner, other.spaceId(), id), CommonErrorCode.RESOURCE_NOT_FOUND);
    assertCode(
        () -> queries.detail(owner, space.spaceId(), UUID.randomUUID()),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    assertCode(
        () -> queries.list(owner, UUID.randomUUID(), null, null, null, 20),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    jdbc.update("UPDATE care_space SET lifecycle = 'DELETING' WHERE id = ?", space.spaceId());
    assertCode(() -> list(owner), CommonErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void filterMustBelongToSpaceAndPermissionIsCheckedBeforeRecordExistence() {
    var first = complete(2);
    complete(1);
    assertThat(
            queries
                .list(owner, space.spaceId(), first.recordId().toString(), null, null, 20)
                .items())
        .hasSize(2)
        .allMatch(p -> p.recordId().equals(first.recordId()));
    assertCode(
        () -> queries.list(owner, space.spaceId(), UUID.randomUUID().toString(), null, null, 20),
        CommonErrorCode.RESOURCE_NOT_FOUND);
    assertCode(
        () -> queries.list(outsider, space.spaceId(), UUID.randomUUID().toString(), null, null, 20),
        CommonErrorCode.FORBIDDEN);
    var other = spaces.create(owner, new CreateCareSpaceRequest("다른 공간", UUID.randomUUID()));
    assertCode(
        () -> queries.list(owner, other.spaceId(), first.recordId().toString(), null, null, 20),
        CommonErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void stablePagingWithEqualTimestampsAndInvalidatedAnchorDoesNotRepeatOrSkip() {
    var c = complete(5);
    Instant time = Instant.parse("2026-10-01T00:00:00Z");
    jdbc.update(
        "UPDATE ai_proposal SET created_at = ? WHERE space_id = ?",
        Timestamp.from(time),
        space.spaceId());
    var expected = list(owner).items().stream().map(ProposalSummary::proposalId).toList();
    assertThat(expected.stream().map(UUID::toString).toList())
        .isSortedAccordingTo(Comparator.reverseOrder());
    var first = queries.list(owner, space.spaceId(), null, null, null, 2);
    assertThat(first.hasNext()).isTrue();
    UUID anchor = first.items().getLast().proposalId();
    jdbc.update("UPDATE ai_proposal SET invalidated_at = now() WHERE id = ?", anchor);
    var second =
        queries.list(owner, space.spaceId(), null, "REVIEW_PENDING", first.nextCursor(), 2);
    var third = queries.list(owner, space.spaceId(), null, null, second.nextCursor(), 2);
    var actual = new ArrayList<UUID>();
    for (var page : List.of(first, second, third))
      actual.addAll(page.items().stream().map(ProposalSummary::proposalId).toList());
    assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    assertThat(third.hasNext()).isFalse();
    assertThat(third.nextCursor()).isNull();
    assertThat(c.recordId()).isNotNull();
  }

  @Test
  void cursorIsBoundToSpaceFiltersAndIntegrityAndDoesNotFreezeNewItems() {
    var c = complete(3);
    var first = queries.list(owner, space.spaceId(), null, null, null, 1);
    String cursor = first.nextCursor();
    assertCode(
        () -> queries.list(owner, space.spaceId(), c.recordId().toString(), null, cursor, 1),
        CommonErrorCode.INVALID_REQUEST);
    assertCode(
        () -> queries.list(owner, space.spaceId(), null, "INVALIDATED", cursor, 1),
        CommonErrorCode.INVALID_REQUEST);
    assertCode(
        () -> queries.list(owner, UUID.randomUUID(), null, null, cursor, 1),
        CommonErrorCode.INVALID_REQUEST);
    String payload =
        new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
    String[] parts = payload.split("\\|");
    parts[4] = "2025-01-01T00:00:00Z";
    String tampered =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                String.join("|", parts).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertCode(
        () -> queries.list(owner, space.spaceId(), null, null, tampered, 1),
        CommonErrorCode.INVALID_REQUEST);
    String foreign = sourceCursors.encode(space.spaceId(), Instant.now(), UUID.randomUUID());
    assertCode(
        () -> queries.list(owner, space.spaceId(), null, null, foreign, 1),
        CommonErrorCode.INVALID_REQUEST);
    var added = complete(1);
    var continued = queries.list(owner, space.spaceId(), null, null, cursor, 20);
    assertThat(continued.items()).hasSize(2).noneMatch(p -> p.recordId().equals(added.recordId()));
    assertThat(list(owner).items().getFirst().recordId()).isEqualTo(added.recordId());
    end();
    assertCode(
        () -> queries.list(member, space.spaceId(), null, null, cursor, 1),
        CommonErrorCode.FORBIDDEN);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "erase",
        "cascade",
        "version",
        "document",
        "evidence",
        "dependency-erased",
        "dependency-version"
      })
  void invalidContentIsHiddenEvenIfPhysicalCascadeHasNotYetRun(String mode) throws Exception {
    var c = complete(1);
    UUID id = list(owner).items().getFirst().proposalId();
    switch (mode) {
      case "erase" -> erase(c);
      case "cascade" ->
          new TransactionTemplate(transactions)
              .executeWithoutResult(
                  tx -> {
                    execution.invalidateSource(space.spaceId(), c.recordId());
                    erase(c);
                  });
      case "version" ->
          jdbc.update("UPDATE source_record SET version = version + 1 WHERE id = ?", c.recordId());
      case "document" ->
          jdbc.update(
              "UPDATE content_document SET body = NULL, redacted_at = now() WHERE space_id = ?",
              space.spaceId());
      case "evidence" ->
          jdbc.update(
              "UPDATE evidence SET quote = NULL, redacted_at = now() WHERE record_id = ?",
              c.recordId());
      default -> {
        var dependency = create();
        jdbc.update(
            "INSERT INTO analysis_input_source (space_id, run_id, source_record_id, source_version) VALUES (?, ?, ?, 1)",
            space.spaceId(),
            c.analysis().analysisId(),
            dependency.recordId());
        if (mode.equals("dependency-erased")) erase(dependency);
        else
          jdbc.update("UPDATE source_record SET version = 2 WHERE id = ?", dependency.recordId());
      }
    }
    var before = snapshot();
    assertThat(list(owner).items()).isEmpty();
    var invalid =
        queries.list(owner, space.spaceId(), c.recordId().toString(), "INVALIDATED", null, 20);
    assertThat(invalid.items())
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.title()).isNull();
              assertThat(p.status()).isEqualTo(ProposalStatus.INVALIDATED);
            });
    var detail = queries.detail(owner, space.spaceId(), id);
    assertThat(detail.proposedValues()).isNull();
    assertThat(detail.title()).isNull();
    assertThat(detail.evidence()).isEmpty();
    assertThat(detail.redactedFields()).containsExactly("title", "proposedValues", "evidence");
    var response =
        mvc.perform(get(path() + "/" + id).with(authentication(login(member))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("INVALIDATED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain("합성 원본", "제안 OBSERVATION", "가족");
    assertThat(snapshot()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "direct",
        "source",
        "deleted",
        "version",
        "document",
        "document-replaced",
        "reconfirmation",
        "evidence-redacted",
        "evidence-removed",
        "evidence-detached",
        "evidence-reassigned",
        "source-erased",
        "source-version",
        "legacy"
      })
  void completedProposalRevalidatesCapturedCandidateInputsWithoutWrites(String change)
      throws Exception {
    var item =
        careItems.create(
            owner,
            space.spaceId(),
            new CreateCareItemRequest(
                "SCHEDULE",
                "참고 일정",
                "참고 설명",
                new ScheduleFields(null, null, null),
                UUID.randomUUID()));
    UUID document =
        jdbc.queryForObject(
            "SELECT current_document_id FROM care_item WHERE id=?", UUID.class, item.itemId());
    var basisSource = create();
    UUID evidence = UUID.randomUUID();
    if (!change.equals("direct")) {
      jdbc.update(
          "INSERT INTO evidence (id,space_id,record_id,record_version,quote,created_at) VALUES (?,?,?,1,'합성 원본 소식',now())",
          evidence,
          space.spaceId(),
          basisSource.recordId());
      jdbc.update(
          "INSERT INTO care_item_basis (id,space_id,item_id,source_evidence_id,created_at) VALUES (?,?,?,?,now())",
          UUID.randomUUID(),
          space.spaceId(),
          item.itemId(),
          evidence);
    }
    var source = complete(1);
    UUID run = source.analysis().analysisId();
    UUID proposal = list(owner).items().getFirst().proposalId();
    assertThat(
            jdbc.queryForObject(
                "SELECT cardinality(source_evidence_ids) FROM analysis_input_item WHERE run_id=? AND item_id=?",
                Integer.class,
                run,
                item.itemId()))
        .isEqualTo(change.equals("direct") ? 0 : 1);
    assertThat(queries.detail(owner, space.spaceId(), proposal).status())
        .isEqualTo(ProposalStatus.REVIEW_PENDING);
    switch (change) {
      case "deleted" ->
          jdbc.update("UPDATE care_item SET visibility='DELETED' WHERE id=?", item.itemId());
      case "version" ->
          jdbc.update("UPDATE care_item SET version=version+1 WHERE id=?", item.itemId());
      case "document" ->
          jdbc.update(
              "UPDATE content_document SET body=NULL,redacted_at=now() WHERE id=?", document);
      case "document-replaced" -> {
        UUID replacement = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO content_document (id,space_id,kind,body,created_at) VALUES (?,?,'DIRECT_INPUT','{}',now())",
            replacement,
            space.spaceId());
        jdbc.update(
            "UPDATE care_item SET current_document_id=? WHERE id=?", replacement, item.itemId());
      }
      case "reconfirmation" ->
          jdbc.update("UPDATE care_item SET needs_reconfirmation=true WHERE id=?", item.itemId());
      case "evidence-redacted" ->
          jdbc.update("UPDATE evidence SET quote=NULL,redacted_at=now() WHERE id=?", evidence);
      case "evidence-removed" -> {
        jdbc.update("DELETE FROM care_item_basis WHERE source_evidence_id=?", evidence);
        jdbc.update("DELETE FROM evidence WHERE id=?", evidence);
      }
      case "evidence-detached", "evidence-reassigned" -> {
        jdbc.update(
            "DELETE FROM care_item_basis WHERE item_id=? AND source_evidence_id=?",
            item.itemId(),
            evidence);
        if (change.equals("evidence-reassigned")) {
          var other =
              careItems.create(
                  owner,
                  space.spaceId(),
                  new CreateCareItemRequest(
                      "SCHEDULE",
                      "다른 일정",
                      null,
                      new ScheduleFields(null, null, null),
                      UUID.randomUUID()));
          jdbc.update(
              "INSERT INTO care_item_basis (id,space_id,item_id,source_evidence_id,created_at) VALUES (?,?,?,?,now())",
              UUID.randomUUID(),
              space.spaceId(),
              other.itemId(),
              evidence);
        }
        assertThat(
                jdbc.queryForObject(
                    "SELECT e.quote IS NOT NULL AND e.redacted_at IS NULL AND r.lifecycle='ACTIVE' AND r.version=e.record_version FROM evidence e JOIN source_record r ON r.id=e.record_id WHERE e.id=?",
                    Boolean.class,
                    evidence))
            .isTrue();
      }
      case "source-erased" -> erase(basisSource);
      case "source-version" ->
          jdbc.update(
              "UPDATE source_record SET version=version+1 WHERE id=?", basisSource.recordId());
      case "legacy" ->
          jdbc.update(
              "UPDATE analysis_input_item SET source_evidence_ids=NULL WHERE run_id=?", run);
      default -> {}
    }
    boolean valid = change.equals("direct") || change.equals("source");
    var before = snapshot();
    assertThat(jdbc.queryForObject("SELECT status FROM analysis_run WHERE id=?", String.class, run))
        .isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT d.body::text FROM ai_proposal p JOIN content_document d ON d.id=p.output_document_id WHERE p.id=?",
                String.class,
                proposal))
        .contains("제안 OBSERVATION", "합성 설명");
    var detail = queries.detail(member, space.spaceId(), proposal);
    assertThat(detail.status())
        .isEqualTo(valid ? ProposalStatus.REVIEW_PENDING : ProposalStatus.INVALIDATED);
    var pending =
        queries.list(owner, space.spaceId(), source.recordId().toString(), null, null, 20);
    var invalidated =
        queries.list(owner, space.spaceId(), source.recordId().toString(), "INVALIDATED", null, 20);
    assertThat(pending.items()).hasSize(valid ? 1 : 0);
    assertThat(invalidated.items()).hasSize(valid ? 0 : 1);
    if (valid) {
      assertThat(detail.proposedValues()).isNotNull();
      assertThat(detail.evidence()).hasSize(1);
      assertThat(detail.candidates()).isEmpty();
      assertThat(detail.targetItemId()).isNull();
    } else {
      assertThat(detail.title()).isNull();
      assertThat(detail.proposedValues()).isNull();
      assertThat(detail.evidence()).isEmpty();
      assertThat(detail.redactedFields()).containsExactly("title", "proposedValues", "evidence");
      assertThat(invalidated.items().getFirst().title()).isNull();
      String body =
          mvc.perform(get(path() + "/" + proposal).with(authentication(login(member))))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.status").value("INVALIDATED"))
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body).doesNotContain("참고 일정", "참고 설명", "합성 원본 소식", "제안 OBSERVATION", "합성 설명");
    }
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void endedOrForeignAssigneeIsNullAndWithdrawnAuthorIsAnonymized() {
    complete(
        List.of(
            suggestion(CareItemType.TASK, Map.of("assigneeMembershipId", membership.toString()))));
    UUID id = list(owner).items().getFirst().proposalId();
    assertThat(
            ((ProposalValues.Task) queries.detail(owner, space.spaceId(), id).proposedValues())
                .assigneeMembershipId())
        .isEqualTo(membership);
    end();
    assertThat(
            ((ProposalValues.Task) queries.detail(owner, space.spaceId(), id).proposedValues())
                .assigneeMembershipId())
        .isNull();
    assertThat(
            queries.detail(owner, space.spaceId(), id).evidence().getFirst().author().withdrawn())
        .isFalse();
    jdbc.update("UPDATE space_membership SET user_id = NULL WHERE id = ?", membership);
    assertThat(queries.detail(owner, space.spaceId(), id).evidence().getFirst().author())
        .isEqualTo(new SourceRecordAuthor(null, null, true));
    var other = spaces.create(outsider, new CreateCareSpaceRequest("다른 공간", UUID.randomUUID()));
    UUID foreign =
        jdbc.queryForObject(
            "SELECT owner_membership_id FROM care_space WHERE id = ?", UUID.class, other.spaceId());
    join(member);
    complete(
        List.of(suggestion(CareItemType.TASK, Map.of("assigneeMembershipId", foreign.toString()))));
    var latest = list(owner).items().getFirst();
    assertThat(
            ((ProposalValues.Task)
                    queries.detail(owner, space.spaceId(), latest.proposalId()).proposedValues())
                .assigneeMembershipId())
        .isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "lower",
        "upper",
        "mixed",
        "missing",
        "empty",
        "malformed",
        "noncanonical",
        "foreign",
        "ended",
        "unknown"
      })
  void assigneeLookupUsesUuidValuesAndSafelyIgnoresInvalidOrIneligibleValues(String mode)
      throws Exception {
    String value = membership.toString();
    switch (mode) {
      case "upper", "ended" -> value = value.toUpperCase(Locale.ROOT);
      case "mixed" -> value = value.substring(0, 18).toUpperCase(Locale.ROOT) + value.substring(18);
      case "missing" -> value = null;
      case "empty" -> value = "";
      case "malformed" -> value = "not-a-uuid";
      case "noncanonical" -> value = "1-1-1-1-1";
      case "unknown" -> value = UUID.randomUUID().toString();
      case "foreign" -> {
        var other = spaces.create(outsider, new CreateCareSpaceRequest("다른 공간", UUID.randomUUID()));
        value =
            jdbc.queryForObject(
                    "SELECT owner_membership_id::text FROM care_space WHERE id=?",
                    String.class,
                    other.spaceId())
                .toUpperCase(Locale.ROOT);
      }
      default -> {}
    }
    complete(
        List.of(
            suggestion(
                CareItemType.TASK,
                value == null ? Map.of() : Map.of("assigneeMembershipId", value))));
    UUID proposal = list(owner).items().getFirst().proposalId();
    if (mode.equals("ended")) end();
    boolean valid = List.of("lower", "upper", "mixed").contains(mode);
    var before = snapshot();
    var detail = queries.detail(owner, space.spaceId(), proposal);
    assertThat(((ProposalValues.Task) detail.proposedValues()).assigneeMembershipId())
        .isEqualTo(valid ? membership : null);
    var response =
        mvc.perform(get(path() + "/" + proposal).with(authentication(login(owner))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var assignee = mapper.readTree(response).at("/data/proposedValues/assigneeMembershipId");
    if (valid) assertThat(assignee.asText()).isEqualTo(membership.toString());
    else assertThat(assignee.isNull()).isTrue();
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void emptyAnalysisAndQueuedRecordHaveEmptyListsAndDefaultAndMaximumSizeWork() throws Exception {
    complete(0);
    create();
    assertThat(list(owner)).isEqualTo(new ProposalListResponse(List.of(), null, false));
    complete(30);
    mvc.perform(get(path()).with(authentication(login(owner))))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.data.items.length()").value(20))
        .andExpect(jsonPath("$.data.hasNext").value(true));
    mvc.perform(get(path()).param("size", "50").with(authentication(login(member))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(30))
        .andExpect(jsonPath("$.data.hasNext").value(false));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "size=0",
        "size=51",
        "size=x",
        "recordId=",
        "recordId=invalid",
        "status=",
        "status=APPROVAL_REQUESTED",
        "cursor=",
        "cursor=broken"
      })
  void rejectsUnsupportedOrMalformedFilters(String input) throws Exception {
    var parts = input.split("=", -1);
    mvc.perform(get(path()).param(parts[0], parts[1]).with(authentication(login(owner))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.errors[0].field").value(parts[0]));
  }

  @Test
  void readsDoNotWaitForSpaceWriteLocks() throws Exception {
    complete(1);
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var writer =
          pool.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .executeWithoutResult(
                          tx -> {
                            jdbc.queryForObject(
                                "SELECT id FROM care_space WHERE id = ? FOR UPDATE",
                                UUID.class,
                                space.spaceId());
                            locked.countDown();
                            try {
                              if (!release.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("reader stalled");
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(e);
                            }
                          }));
      try {
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pool.submit(() -> list(owner)).get(3, TimeUnit.SECONDS).items()).hasSize(1);
      } finally {
        release.countDown();
      }
      writer.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void swaggerDocumentsReadOnlyEndpointsTypedValuesAndSessionSecurity() throws Exception {
    var root =
        mapper.readTree(
            mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var paths = root.path("paths");
    for (String p :
        List.of(
            "/api/spaces/{spaceId}/proposals", "/api/spaces/{spaceId}/proposals/{proposalId}")) {
      var get = paths.path(p).path("get");
      assertThat(get.path("responses").has("200")).isTrue();
      assertThat(get.path("security").toString()).contains("SessionCookie").doesNotContain("Csrf");
      assertThat(get.path("parameters").toString()).doesNotContain("X-CSRF-TOKEN", "currentUser");
      assertThat(paths.path(p).has("post")).isFalse();
    }
    var schemas = root.path("components").path("schemas");
    assertThat(schemas.path("ProposalValues").path("oneOf").size()).isEqualTo(8);
    assertThat(schemas.path("ScheduleProposalValues").path("properties").has("scheduledDate"))
        .isTrue();
    assertThat(schemas.path("TaskProposalValues").path("properties").has("assigneeMembershipId"))
        .isTrue();
    assertThat(schemas.path("QuestionProposalValues").path("properties").has("confirmedAnswer"))
        .isFalse();
    assertThat(schemas.path("ProposalDetail").path("properties").has("evidence")).isTrue();
    assertThat(schemas.path("V2QuestionProposalValues").path("properties").has("answerText"))
        .isTrue();
    for (String name : List.of("changedFields", "clearFields", "reviewReasons", "comparisonBasis"))
      assertThat(schemas.path("ProposalDetail").path("properties").has(name)).isTrue();
    for (String name : List.of("currentValues", "proposedValues")) {
      var property = schemas.path("ProposalDetail").path("properties").path(name);
      assertThat(property.has("type")).isFalse();
      assertThat(property.has("$ref")).isFalse();
      assertThat(property.path("anyOf").size()).isEqualTo(2);
      assertThat(property.at("/anyOf/0/$ref").asText())
          .isEqualTo("#/components/schemas/ProposalValues");
      assertThat(property.at("/anyOf/1/type").asText()).isEqualTo("null");
    }
    assertThat(schemas.path("QuestionProposalValues").path("additionalProperties").asBoolean())
        .isFalse();
    assertThat(schemas.path("ScheduleProposalValues").path("required").size()).isEqualTo(5);
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
        member, space.spaceId(), new CreateSourceRecordRequest("합성 원본 소식", UUID.randomUUID()));
  }

  private CreateSourceRecordResponse complete(int size) {
    var suggestions = new ArrayList<AiAnalysisResponse.Suggestion>();
    for (int i = 0; i < size; i++)
      suggestions.add(suggestion(CareItemType.OBSERVATION, Map.of("content", "합성 설명")));
    return complete(suggestions);
  }

  private CreateSourceRecordResponse complete(List<AiAnalysisResponse.Suggestion> suggestions) {
    var c = create();
    var work =
        execution
            .claim(space.spaceId(), c.analysis().analysisId(), Duration.ofMinutes(2))
            .orElseThrow();
    assertThat(
            execution.complete(
                work, new AiAnalysisResponse(work.request().requestId(), suggestions)))
        .isTrue();
    return c;
  }

  private AiAnalysisResponse.Suggestion suggestion(CareItemType type, Map<String, String> changes) {
    return new AiAnalysisResponse.Suggestion(
        AiAnalysisResponse.Operation.CREATE,
        type,
        null,
        null,
        List.of(),
        "제안 " + type,
        "합성 원본 소식",
        changes);
  }

  private ProposalListResponse list(UUID user) {
    return queries.list(user, space.spaceId(), null, null, null, 20);
  }

  private String path() {
    return "/api/spaces/" + space.spaceId() + "/proposals";
  }

  private OAuth2AuthenticationToken login(UUID id) {
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    return new OAuth2AuthenticationToken(
        new MemberOAuth2User(
            id, new DefaultOAuth2User(authorities, Map.of("id", id.toString()), "id")),
        authorities,
        "kakao");
  }

  private void erase(CreateSourceRecordResponse c) {
    jdbc.update(
        "UPDATE source_record SET lifecycle = 'ERASED', body = NULL, erased_at = now(), version = version + 1 WHERE id = ?",
        c.recordId());
  }

  private Map<String, Object> snapshot() {
    var result = new LinkedHashMap<String, Object>();
    for (String table :
        List.of(
            "care_space",
            "source_record",
            "analysis_run",
            "ai_proposal",
            "content_document",
            "evidence",
            "care_item",
            "care_item_basis"))
      result.put(
          table,
          jdbc.queryForList(
              "SELECT * FROM "
                  + table
                  + " WHERE "
                  + (table.equals("care_space") ? "id" : "space_id")
                  + " = ? ORDER BY id",
              space.spaceId()));
    result.put(
        "receipts",
        jdbc.queryForList(
            "SELECT * FROM command_receipt WHERE actor_user_id IN (?, ?) ORDER BY id",
            owner,
            member));
    return result;
  }

  private static void assertCode(Runnable runnable, CommonErrorCode code) {
    assertThatThrownBy(runnable::run)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
  }
}
