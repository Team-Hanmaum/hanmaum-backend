package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hanmaum.backend.careitem.dto.*;
import com.hanmaum.backend.careitem.repository.CareItemHistoryRepository;
import com.hanmaum.backend.careitem.service.*;
import com.hanmaum.backend.carespace.dto.*;
import com.hanmaum.backend.carespace.service.CareSpaceCreationService;
import com.hanmaum.backend.global.security.oauth.MemberOAuth2User;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DashboardIntegrationTests.TimeConfig.class})
class DashboardIntegrationTests {
  // 11월 1일 00:30 KST, 아직 UTC로는 전날.
  static final Instant NOW = Instant.parse("2026-10-31T15:30:00Z");

  @TestConfiguration
  static class TimeConfig {
    @Bean
    @Primary
    Clock dashboardTestClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired CareSpaceCreationService spaces;
  @Autowired CareItemCreationService create;
  @Autowired DirectCareItemUpdateService update;
  @Autowired EditLockService locks;
  @MockitoSpyBean CareItemHistoryRepository history;
  UUID owner, member, space, membership;

  @BeforeEach
  void setup() {
    owner = user("소유자");
    member = user("수진");
    space = spaces.create(owner, new CreateCareSpaceRequest("돌봄", UUID.randomUUID())).spaceId();
    membership = UUID.randomUUID();
    db.update(
        "INSERT INTO space_membership(id,space_id,user_id,joined_at) VALUES (?,?,?,now())",
        membership,
        space,
        member);
  }

  @Test
  void emptyAndAuthorization() throws Exception {
    var empty = data("/dashboard");
    assertThat(empty.path("asOf").asText()).isEqualTo(NOW.toString());
    for (var name :
        List.of("upcomingSchedules", "remainingTasks", "unconfirmedQuestions", "recentChanges")) {
      assertThat(empty.path(name).path("totalCount").asLong()).isZero();
      assertThat(empty.path(name).path("items").isEmpty()).isTrue();
    }
    assertThat(empty.path("pastScheduleCount").asLong()).isZero();
    String dash = base() + "/dashboard";
    mvc.perform(get(dash)).andExpect(status().isUnauthorized());
    mvc.perform(get(dash).with(authentication(login(user("다른 사람")))))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/spaces/" + UUID.randomUUID() + "/dashboard")
                .with(authentication(login(owner))))
        .andExpect(status().isNotFound());
  }

  @Test
  void seoulBoundaryOrderCountAndPartialTimesAgreeBetweenHomeAndTab() throws Exception {
    schedule("시간 미정", "2026-11-01", null);
    schedule("오늘 직전", "2026-11-01", "00:29");
    schedule("날짜 미정", null, null);
    schedule("전날 시간 미정", "2026-10-31", null);
    schedule("전날 오후", "2026-10-31", "14:00");
    schedule("오늘 경계", "2026-11-01", "00:30");
    schedule("내일", "2026-11-02", null);
    var past = list("SCHEDULE", "PAST");
    var up = list("SCHEDULE", "UPCOMING");
    var home = data("/dashboard");
    assertThat(titles(past.path("items"))).containsExactly("오늘 직전", "전날 오후", "전날 시간 미정");
    assertThat(titles(up.path("items"))).containsExactly("오늘 경계", "시간 미정", "내일", "날짜 미정");
    assertThat(home.path("pastScheduleCount")).isEqualTo(up.path("pastScheduleCount"));
    assertThat(up.path("pastScheduleCount")).isEqualTo(past.path("totalCount"));
    assertSection(up, home.path("upcomingSchedules"), 2);
    assertThat(data("/items?itemType=SCHEDULE&status=SCHEDULED").path("items").size()).isEqualTo(7);
  }

  @Test
  void taskAndQuestionPreviewsTrackActualWritesAndVersions() throws Exception {
    var a = task("진행 중", "2026-11-02");
    task("기한 미정", null);
    task("먼저", "2026-11-01");
    task("동일 기한", "2026-11-02");
    change(member, a, 1, "IN_PROGRESS");
    for (String title : List.of("질문 A", "질문 B", "질문 C"))
      create.create(
          owner,
          space,
          new CreateCareItemRequest(
              "QUESTION",
              title,
              null,
              new CreateCareItemRequest.QuestionFields(),
              UUID.randomUUID()));
    var home = data("/dashboard");
    var tasks = list("TASK", "REMAINING");
    assertThat(titles(tasks.path("items"))).containsExactly("먼저", "진행 중", "동일 기한", "기한 미정");
    assertThat(tasks.at("/items/0/assigneeDisplayName").asText()).isEqualTo("수진");
    assertSection(tasks, home.path("remainingTasks"), 3);
    assertSection(list("QUESTION", "UNCONFIRMED"), home.path("unconfirmedQuestions"), 2);
    change(member, a, 2, "COMPLETED");
    assertThat(titles(list("TASK", "COMPLETED").path("items"))).containsExactly("진행 중");
    var after = data("/dashboard");
    assertSection(list("TASK", "REMAINING"), after.path("remainingTasks"), 3);
    assertThat(after.at("/remainingTasks/totalCount").asLong()).isEqualTo(3);
    assertThat(after.at("/recentChanges/totalCount").asLong()).isEqualTo(9);
    // Distinct versions remain separate changes, including direct creation and member status
    // changes.
    db.update(
        "UPDATE care_item_history SET changed_at=? WHERE item_id=? AND version>=2",
        Timestamp.from(NOW.plusSeconds(1)),
        a);
    var recent = data("/dashboard").at("/recentChanges/items");
    assertThat(recent.size()).isEqualTo(3);
    assertThat(recent.get(0).get("version").asText()).isEqualTo("3");
    assertThat(recent.get(1).get("version").asText()).isEqualTo("2");
    assertThat(recent.get(0).get("changedByMembershipId").asText())
        .isEqualTo(membership.toString());
  }

  @Test
  void closedOrderUsesLastStatusChangeAndNotLaterContentEdits() throws Exception {
    UUID a = schedule("완료", "2026-11-01", null);
    UUID b = schedule("취소", "2026-11-02", null);
    change(owner, a, 1, "COMPLETED");
    change(owner, b, 1, "CANCELED");
    db.update(
        "UPDATE care_item_history SET changed_at=? WHERE item_id=? AND version=2",
        Timestamp.from(NOW.minusSeconds(30)),
        a);
    var command = command(owner, a, 2);
    command.putObject("values").put("title", "수정된 완료");
    update.update(owner, space, a, command, false);
    assertThat(titles(list("SCHEDULE", "CLOSED").path("items"))).containsExactly("취소", "수정된 완료");
    assertThat(data("/dashboard").at("/upcomingSchedules/totalCount").asLong()).isZero();
  }

  @Test
  void redactionReconfirmationAndConflictNeverLeakIntoCountsOrRecentChanges() throws Exception {
    UUID redacted = schedule("지울 제목", "2026-12-01", null);
    UUID check = schedule("재확인 전 내용", "2026-10-30", null);
    UUID visible = schedule("상충 일정", "2026-11-03", null);
    UUID deleted = schedule("삭제 항목", "2026-11-04", null);
    db.update(
        "UPDATE content_document SET body=NULL,redacted_at=now() WHERE id=(SELECT current_document_id FROM care_item WHERE id=?)",
        redacted);
    db.update("UPDATE care_item SET needs_reconfirmation=true WHERE id=?", check);
    db.update("UPDATE care_item SET needs_conflict_review=true WHERE id=?", visible);
    db.update("UPDATE care_item SET visibility='DELETED' WHERE id=?", deleted);
    var home = data("/dashboard");
    assertThat(home.at("/upcomingSchedules/totalCount").asInt()).isOne();
    assertThat(home.at("/upcomingSchedules/items/0/needsConflictReview").asBoolean()).isTrue();
    assertThat(home.at("/recentChanges/totalCount").asInt()).isOne();
    var up = list("SCHEDULE", "UPCOMING");
    assertThat(up.path("totalCount").asInt()).isOne();
    assertThat(up.path("reconfirmationItems").size()).isEqualTo(2);
    assertThat(list("SCHEDULE", "PAST").path("reconfirmationItems"))
        .isEqualTo(up.path("reconfirmationItems"));
    assertThat(up.toString()).doesNotContain("지울 제목", "재확인 전 내용", "삭제 항목");
    assertThat(home.toString())
        .doesNotContain(redacted.toString(), check.toString(), deleted.toString());
  }

  @Test
  void membershipAndAllSectionsReadOneSnapshotEvenWhenAnotherTransactionCommits() throws Exception {
    schedule("기존", "2026-12-01", null);
    doAnswer(
            call -> {
              CompletableFuture.runAsync(() -> schedule("동시 등록", "2026-12-02", null))
                  .get(10, TimeUnit.SECONDS);
              return call.callRealMethod();
            })
        .doCallRealMethod()
        .when(history)
        .recentChanges(space);
    var before = data("/dashboard");
    assertThat(before.at("/upcomingSchedules/totalCount").asInt()).isOne();
    assertThat(before.at("/recentChanges/totalCount").asInt()).isOne();
    var after = data("/dashboard");
    assertThat(after.at("/upcomingSchedules/totalCount").asInt()).isEqualTo(2);
    assertThat(after.at("/recentChanges/totalCount").asInt()).isEqualTo(2);
  }

  @Test
  void invalidFiltersAndSectionFailureHaveNoPartialSuccess() throws Exception {
    for (String q :
        List.of(
            "itemType=TASK&view=PAST",
            "view=UPCOMING",
            "itemType=SCHEDULE&status=SCHEDULED&view=PAST",
            "itemType=QUESTION&view=oops",
            "itemType=SCHEDULE&scheduleView=PAST")) {
      mvc.perform(get(base() + "/items?" + q).with(authentication(login(member))))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
    doThrow(new DataAccessResourceFailureException("test")).when(history).recentChanges(space);
    mvc.perform(get(base() + "/dashboard").with(authentication(login(member))))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.data").isEmpty());
  }

  @Test
  void observationAndGuidanceUseTheirOwnDateAndSortInvalidOrMissingDatesLast() throws Exception {
    for (String type : List.of("OBSERVATION", "GUIDANCE")) {
      String field = type.equals("OBSERVATION") ? "observedDate" : "receivedDate";
      for (var pair :
          List.of(
              List.of("옛 기록", "2026-10-01"),
              List.of("최근 기록", "2026-10-30"),
              List.of("날짜 오류", "2026-02-30"),
              List.of("날짜 없음", ""))) {
        UUID item = schedule(pair.get(0), null, null);
        db.update("UPDATE care_item SET item_type=?,status=NULL WHERE id=?", type, item);
        db.update(
            "UPDATE content_document SET body=jsonb_set(body,'{fields}',CAST(? AS jsonb)) WHERE id=(SELECT current_document_id FROM care_item WHERE id=?)",
            pair.get(1).isEmpty() ? "{}" : mapper.writeValueAsString(Map.of(field, pair.get(1))),
            item);
        db.update("UPDATE care_item_history SET status=NULL WHERE item_id=?", item);
      }
      var names = titles(data("/items?itemType=" + type).path("items"));
      assertThat(names.subList(0, 2)).containsExactly("최근 기록", "옛 기록");
      assertThat(names.subList(2, 4)).containsExactlyInAnyOrder("날짜 오류", "날짜 없음");
    }
  }

  @Test
  void recentHistoryRequiresItsOwnDocumentEvenIfTheCurrentDocumentIsReadable() throws Exception {
    UUID item = schedule("지워진 과거 제목", "2026-12-01", null);
    UUID old =
        db.queryForObject("SELECT current_document_id FROM care_item WHERE id=?", UUID.class, item);
    // A later independently confirmed document has no dependency on the redacted document.
    UUID fresh = UUID.randomUUID();
    db.update(
        "INSERT INTO content_document(id,space_id,kind,body,created_at) VALUES (?,?,'CONFIRMED_ITEM',CAST(? AS jsonb),now())",
        fresh,
        space,
        mapper.writeValueAsString(
            Map.of("title", "다시 확인한 제목", "fields", Map.of("scheduledDate", "2026-12-01"))));
    db.update("UPDATE care_item SET current_document_id=?,version=2 WHERE id=?", fresh, item);
    db.update(
        "INSERT INTO care_item_history SELECT space_id,id,version,'UPDATE',current_document_id,status,created_by_membership_id,now() FROM care_item WHERE id=?",
        item);
    db.update("UPDATE content_document SET body=NULL,redacted_at=now() WHERE id=?", old);
    var home = data("/dashboard");
    assertThat(home.at("/recentChanges/totalCount").asLong()).isOne();
    assertThat(home.at("/recentChanges/items/0/version").asText()).isEqualTo("2");
    assertThat(home.toString()).doesNotContain("지워진 과거 제목").contains("다시 확인한 제목");
  }

  @Test
  void previewSkipsNewestBlockedVersionsAndKeepsExactCountAcrossSharedDocuments() throws Exception {
    UUID visible = schedule("확정", "2026-11-10", null);
    UUID root = schedule("원본", "2026-11-11", null);
    UUID derived = schedule("이미 복사된 제목", "2026-11-12", null);
    db.update(
        """
        INSERT INTO care_item_document_parent(space_id,document_id,parent_document_id)
        SELECT i.space_id,i.current_document_id,p.current_document_id FROM care_item i,care_item p
        WHERE i.id=? AND p.id=?
        """,
        derived,
        root);
    db.update(
        "UPDATE content_document SET body=NULL,redacted_at=now() WHERE id=(SELECT current_document_id FROM care_item WHERE id=?)",
        root);
    for (UUID item : List.of(visible, derived)) {
      // LINK_EVIDENCE creates another version with the same document.
      db.update(
          """
          INSERT INTO care_item_history(space_id,item_id,version,operation,document_id,status,changed_by_membership_id,changed_at)
          SELECT i.space_id,i.id,v,'LINK_EVIDENCE',i.current_document_id,i.status,i.created_by_membership_id,
                 CAST(? AS timestamptz) + v * interval '1 second'
          FROM care_item i CROSS JOIN generate_series(2,512) v WHERE i.id=?
          """,
          Timestamp.from(item.equals(visible) ? NOW : NOW.plusSeconds(1000)),
          item);
      db.update("UPDATE care_item SET version=512 WHERE id=?", item);
    }
    var recent = data("/dashboard").path("recentChanges");
    assertThat(recent.path("totalCount").asLong()).isEqualTo(512);
    assertThat(recent.path("items"))
        .extracting(row -> row.path("version").asText())
        .containsExactly("512", "511", "510");
    assertThat(recent.toString()).doesNotContain(derived.toString(), root.toString());
  }

  private void assertSection(JsonNode full, JsonNode preview, int cap) {
    assertThat(preview.path("totalCount")).isEqualTo(full.path("totalCount"));
    assertThat(preview.path("items").size()).isEqualTo(Math.min(cap, full.path("items").size()));
    for (int i = 0; i < preview.path("items").size(); i++)
      assertThat(preview.path("items").get(i)).isEqualTo(full.path("items").get(i));
  }

  private UUID schedule(String title, String date, String time) {
    return create
        .create(
            owner,
            space,
            new CreateCareItemRequest(
                "SCHEDULE", title, null, new ScheduleFields(date, time, null), UUID.randomUUID()))
        .itemId();
  }

  private UUID task(String title, String due) {
    return create
        .create(
            owner,
            space,
            new CreateCareItemRequest(
                "TASK",
                title,
                null,
                new CreateCareItemRequest.TaskFields(due, membership.toString()),
                UUID.randomUUID()))
        .itemId();
  }

  private void change(UUID actor, UUID item, long version, String next) {
    update.update(actor, space, item, command(actor, item, version).put("status", next), true);
  }

  private tools.jackson.databind.node.ObjectNode command(UUID actor, UUID item, long version) {
    return mapper
        .createObjectNode()
        .put("clientRequestId", UUID.randomUUID().toString())
        .put("expectedItemVersion", Long.toString(version))
        .put("leaseToken", locks.acquire(actor, space, item, version).leaseToken());
  }

  private List<String> titles(JsonNode items) {
    List<String> names = new ArrayList<>();
    for (var item : items) names.add(item.path("title").asText());
    return names;
  }

  private UUID user(String name) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO app_user(id,display_name,created_at,updated_at) VALUES (?,?,now(),now())",
        id,
        name);
    db.update(
        "INSERT INTO social_account(id,user_id,provider,provider_user_id,created_at) VALUES (?,?, 'KAKAO',?,now())",
        UUID.randomUUID(),
        id,
        id.toString());
    return id;
  }

  private String base() {
    return "/api/spaces/" + space;
  }

  private JsonNode list(String type, String view) throws Exception {
    return data("/items?itemType=" + type + "&view=" + view);
  }

  private JsonNode data(String path) throws Exception {
    var res =
        mvc.perform(get(base() + path).with(authentication(login(member))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse();
    return mapper.readTree(res.getContentAsByteArray()).path("data");
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
