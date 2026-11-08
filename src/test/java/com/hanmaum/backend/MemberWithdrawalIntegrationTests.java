package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hanmaum.backend.careitem.dto.*;
import com.hanmaum.backend.careitem.service.*;
import com.hanmaum.backend.carespace.dto.*;
import com.hanmaum.backend.carespace.service.*;
import com.hanmaum.backend.global.exception.ApiException;
import com.hanmaum.backend.global.security.oauth.*;
import com.hanmaum.backend.record.dto.*;
import com.hanmaum.backend.record.service.SourceRecordCreationService;
import com.hanmaum.backend.user.entity.SocialProvider;
import com.hanmaum.backend.user.service.*;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.Cookie;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
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
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = "app.analysis.worker.enabled=false")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MemberWithdrawalIntegrationTests {
  private static final String PATH = "/api/users/me/withdrawals";
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc mvc;
  @Autowired CareSpaceCreationService spaces;
  @Autowired InvitationService invitations;
  @Autowired MembershipLifecycleService memberships;
  @Autowired CareItemCreationService items;
  @Autowired EditLockService locks;
  @Autowired SourceRecordCreationService records;
  @Autowired MemberWithdrawalService withdrawals;
  @Autowired SocialLoginMemberService social;
  @Autowired MemberLifecycleAccess lifecycle;
  @Autowired MemberAuthorizedClientRepository clients;
  @Autowired JdbcIndexedSessionRepository sessions;
  @Autowired PlatformTransactionManager transactions;
  @Autowired DataSource dataSource;
  UUID owner, member, space, membership;

  @BeforeEach
  void setup() {
    owner = user();
    member = user();
    var created = spaces.create(owner, new CreateCareSpaceRequest("보존할 공동 공간", UUID.randomUUID()));
    space = created.spaceId();
    membership = join(member, space);
  }

  /** Real JDBC sessions and OAuth client credentials disappear together with the account. */
  @Test
  void deletesAccountAllSessionsAndCredentialsWhileOldCookiesAndIdentityStayUnauthorized()
      throws Exception {
    Cookie first = cookie(member), second = cookie(member), other = cookie(owner);
    jdbc.update(
        "INSERT INTO social_account(id,user_id,provider,provider_user_id,created_at) VALUES (?,?,'GOOGLE',?,now())",
        UUID.randomUUID(),
        member,
        "synthetic-google-" + member);
    var anonymous = sessions.createSession();
    ((org.springframework.session.Session) anonymous).setAttribute("pending-state", "synthetic");
    sessions.save(anonymous);
    var client = client(member);
    org.springframework.session.Session delayedSession = sessions.createSession();
    delayedSession.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(login(member)));
    clients.saveAuthorizedClient(client, login(member), null, null);
    assertThat(clients.<OAuth2AuthorizedClient>loadAuthorizedClient("kakao", login(member), null))
        .isSameAs(client);
    mvc.perform(
            post(PATH)
                .cookie(first)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(command().toString()))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie()
                .maxAge("HANMAUM_SESSION", 0));
    assertThat(count("app_user", "id", member)).isZero();
    assertThat(count("social_account", "user_id", member)).isZero();
    assertThat(count("spring_session", "principal_name", member.toString())).isZero();
    @SuppressWarnings({"unchecked", "rawtypes"})
    org.springframework.session.SessionRepository<org.springframework.session.Session>
        sessionStore = (org.springframework.session.SessionRepository) sessions;
    assertThatThrownBy(() -> sessionStore.save(delayedSession))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(sessions.findById(((org.springframework.session.Session) anonymous).getId()))
        .isNotNull();
    mvc.perform(get("/api/users/me").cookie(other)).andExpect(status().isOk());
    for (Cookie stale : List.of(first, second)) {
      mvc.perform(get("/api/users/me").cookie(stale)).andExpect(status().isUnauthorized());
      mvc.perform(
              post(PATH)
                  .cookie(stale)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(command().toString()))
          .andExpect(status().isUnauthorized());
    }
    assertThat(clients.<OAuth2AuthorizedClient>loadAuthorizedClient("kakao", login(member), null))
        .isNull();
    assertThatThrownBy(() -> clients.saveAuthorizedClient(client, login(member), null, null))
        .isInstanceOf(OAuth2AuthenticationException.class);
    assertCode(
        () -> spaces.create(member, new CreateCareSpaceRequest("복구 금지", UUID.randomUUID())),
        "UNAUTHENTICATED");
    assertCode(() -> withdrawals.withdraw(member, command()), "UNAUTHENTICATED");
  }

  /**
   * Shared records, immutable documents, completed tasks, and historical membership IDs survive.
   */
  @Test
  void preservesSharedContentAnonymizesAllMembershipsAndReleasesOnlyOpenAssignments()
      throws Exception {
    UUID record =
        records
            .create(member, space, new CreateSourceRecordRequest("보존할 합성 원문", UUID.randomUUID()))
            .recordId();
    UUID open = task(), complete = task();
    jdbc.update("UPDATE care_item SET status='COMPLETED' WHERE id=?", complete);
    UUID oldDocument =
        jdbc.queryForObject(
            "SELECT current_document_id FROM care_item WHERE id=?", UUID.class, open);
    // Membership termination must remove every purpose of lock, including the completed task lock.
    locks.acquire(member, space, open, 1);
    locks.acquire(member, space, complete, 1);
    UUID history = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO space_membership(id,space_id,user_id,joined_at,ended_at,end_reason) VALUES (?,?,?,now(),now(),'LEFT')",
        history,
        space,
        member);
    UUID otherSpace =
        spaces.create(owner, new CreateCareSpaceRequest("두 번째 공간", UUID.randomUUID())).spaceId();
    UUID second = join(member, otherSpace);
    UUID request = UUID.randomUUID(),
        entry = UUID.randomUUID(),
        requestDocument = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO content_document(id,space_id,kind,body,created_at) VALUES (?,?,'CHANGE_REQUEST','{\"values\":{\"subjectLabel\":\"변경 대기\"}}',now())",
        requestDocument,
        space);
    jdbc.update(
        "INSERT INTO change_request(id,space_id,submitted_by_membership_id,submitted_at) VALUES (?,?,?,now())",
        request,
        space,
        membership);
    jdbc.update(
        "INSERT INTO change_request_entry(id,space_id,request_id,ordinal_no,source_type,action,expected_space_version,request_document_id) VALUES (?,?,?,0,'DIRECT_INPUT','UPDATE_SPACE',(SELECT version FROM care_space WHERE id=?),?)",
        entry,
        space,
        request,
        space,
        requestDocument);
    assertThat(
            jdbc.queryForObject(
                "SELECT change_request_entry_invalid_reason(?)", String.class, entry))
        .isNull();
    long version =
        jdbc.queryForObject("SELECT version FROM care_space WHERE id=?", Long.class, space);
    long docs = count("content_document", "space_id", space);
    assertThat(count("command_receipt", "actor_user_id", member)).isPositive();
    withdraw(member).andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForList(
                "SELECT user_id FROM space_membership WHERE id IN (?,?,?)",
                membership,
                history,
                second))
        .allSatisfy(row -> assertThat(row.get("user_id")).isNull());
    assertThat(
            jdbc.queryForObject(
                "SELECT end_reason FROM space_membership WHERE id=?", String.class, membership))
        .isEqualTo("WITHDRAWN");
    assertThat(
            jdbc.queryForObject(
                "SELECT end_reason FROM space_membership WHERE id=?", String.class, history))
        .isEqualTo("LEFT");
    assertThat(jdbc.queryForObject("SELECT version FROM care_space WHERE id=?", Long.class, space))
        .isEqualTo(version + 1);
    assertThat(count("source_record", "id", record)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT change_request_entry_invalid_reason(?)", String.class, entry))
        .isEqualTo("REQUESTER_LEFT");
    assertThat(count("change_request", "id", request)).isEqualTo(1);
    assertThat(count("content_document", "space_id", space)).isEqualTo(docs + 1);
    assertThat(count("content_document", "id", oldDocument)).isEqualTo(1);
    assertThat(count("command_receipt", "actor_user_id", member)).isZero();
    assertThat(count("command_receipt", "actor_user_id", owner)).isPositive();
    assertThat(count("care_item_edit_lock", "space_id", space)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT d.body #>> '{fields,assigneeMembershipId}' FROM care_item i JOIN content_document d ON d.id=i.current_document_id WHERE i.id=?",
                String.class,
                open))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT d.body #>> '{fields,assigneeMembershipId}' FROM care_item i JOIN content_document d ON d.id=i.current_document_id WHERE i.id=?",
                String.class,
                complete))
        .isEqualTo(membership.toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM care_item_history WHERE item_id=? AND operation='UNASSIGN'",
                Long.class,
                open))
        .isEqualTo(1);
    mvc.perform(
            get("/api/spaces/" + space + "/source-records/" + record)
                .with(authentication(login(owner))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").value("보존할 합성 원문"))
        .andExpect(jsonPath("$.data.author.withdrawn").value(true))
        .andExpect(jsonPath("$.data.author.membershipId").isEmpty())
        .andExpect(jsonPath("$.data.author.displayName").isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACTIVE", "DELETING"})
  void ownedSpaceBlocksWithdrawalWithoutPartialChanges(String state) throws Exception {
    UUID owned =
        spaces.create(member, new CreateCareSpaceRequest("먼저 이전할 공간", UUID.randomUUID())).spaceId();
    jdbc.update("UPDATE care_space SET lifecycle=? WHERE id=?", state, owned);
    Cookie cookie = cookie(member);
    withdraw(member)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("OWNED_SPACE_REMAINS"));
    assertThat(count("app_user", "id", member)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT is_active FROM space_membership WHERE id=?", Boolean.class, membership))
        .isTrue();
    mvc.perform(get("/api/users/me").cookie(cookie)).andExpect(status().isOk());
  }

  /** Ownership can be transferred away, but cannot be transferred back to an ended membership. */
  @Test
  void ownershipTransferAndWithdrawalPreserveExactlyOneActiveOwner() throws Exception {
    long version =
        jdbc.queryForObject("SELECT version FROM care_space WHERE id=?", Long.class, space);
    memberships.transfer(
        owner,
        space,
        new TransferOwnershipRequest(membership, Long.toString(version), UUID.randomUUID()));
    withdraw(member).andExpect(status().isConflict());
    UUID originalOwner =
        jdbc.queryForObject(
            "SELECT id FROM space_membership WHERE space_id=? AND user_id=?",
            UUID.class,
            space,
            owner);
    memberships.transfer(
        member,
        space,
        new TransferOwnershipRequest(originalOwner, Long.toString(version + 1), UUID.randomUUID()));
    withdraw(member).andExpect(status().isNoContent());
    assertCode(
        () ->
            memberships.transfer(
                owner,
                space,
                new TransferOwnershipRequest(
                    membership, Long.toString(version + 3), UUID.randomUUID())),
        "MEMBERSHIP_STATE_CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "SELECT owner_user_id FROM care_space WHERE id=?", UUID.class, space))
        .isEqualTo(owner);
  }

  @Test
  void freshLoginCreatesNewIdentityWithoutRestoringFormerPermissions() throws Exception {
    withdraw(member).andExpect(status().isNoContent());
    UUID fresh = social.findOrCreate(SocialProvider.KAKAO, member.toString(), "새 회원");
    assertThat(fresh).isNotEqualTo(member);
    assertThat(count("space_membership", "user_id", fresh)).isZero();
    mvc.perform(get("/api/spaces/" + space).with(authentication(login(fresh, member.toString()))))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(PATH)
                .with(authentication(login(member)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(command().toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsMissingAuthenticationCsrfUnknownFieldsAndReceiptReuse() throws Exception {
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(command().toString()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post(PATH)
                .with(authentication(login(member)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(command().toString()))
        .andExpect(status().isForbidden());
    for (String body :
        List.of(
            "{}",
            "null",
            "[]",
            "{\"clientRequestId\":\"wrong\"}",
            command().put("userId", owner.toString()).toString()))
      call(member, body).andExpect(status().isBadRequest());
    UUID key = UUID.randomUUID();
    records.create(member, space, new CreateSourceRecordRequest("기존 명령", key));
    call(member, mapper.createObjectNode().put("clientRequestId", key.toString()).toString())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    call(member, command().put("padding", "x".repeat(1048576)).toString())
        .andExpect(status().isPayloadTooLarge());
    assertThat(count("app_user", "id", member)).isEqualTo(1);
  }

  /** A failure at the final deletion must undo every earlier shared-data and session mutation. */
  @Test
  void databaseFailureRollsBackMembershipTasksLocksReceiptsAndSessions() throws Exception {
    UUID item = task();
    locks.acquire(member, space, item, 1);
    Cookie cookie = cookie(member);
    long receipts = count("command_receipt", "actor_user_id", member);
    String suffix = member.toString().replace("-", "");
    String function = "withdrawal_fail_" + suffix;
    jdbc.execute(
        "CREATE FUNCTION "
            + function
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF OLD.id='"
            + member
            + "'::uuid THEN RAISE EXCEPTION 'synthetic-sensitive-database-error'; END IF; RETURN OLD; END $$");
    jdbc.execute(
        "CREATE TRIGGER "
            + function
            + " BEFORE DELETE ON app_user FOR EACH ROW EXECUTE FUNCTION "
            + function
            + "()");
    try {
      var result = withdraw(member).andExpect(status().isInternalServerError()).andReturn();
      assertThat(result.getResponse().getContentAsString())
          .doesNotContain("synthetic-sensitive", member.toString());
      assertThat(
              jdbc.queryForObject(
                  "SELECT is_active FROM space_membership WHERE id=?", Boolean.class, membership))
          .isTrue();
      assertThat(jdbc.queryForObject("SELECT version FROM care_item WHERE id=?", Long.class, item))
          .isEqualTo(1);
      assertThat(count("command_receipt", "actor_user_id", member)).isEqualTo(receipts);
      assertThat(count("care_item_edit_lock", "item_id", item)).isEqualTo(1);
      mvc.perform(get("/api/users/me").cookie(cookie)).andExpect(status().isOk());
    } finally {
      jdbc.execute("DROP TRIGGER " + function + " ON app_user");
      jdbc.execute("DROP FUNCTION " + function + "()");
    }
    withdraw(member).andExpect(status().isNoContent());
  }

  /** A rolled-back login cannot leave provider tokens in the in-memory authorized-client store. */
  @Test
  void authorizedClientSaveRollsBackWithItsDatabaseTransaction() {
    var tx = new TransactionTemplate(transactions);
    tx.executeWithoutResult(
        status -> {
          clients.saveAuthorizedClient(client(member), login(member), null, null);
          status.setRollbackOnly();
        });
    assertThat(clients.<OAuth2AuthorizedClient>loadAuthorizedClient("kakao", login(member), null))
        .isNull();
  }

  /**
   * Proves actual PostgreSQL lock waits before committing deletion, rather than relying on sleeps.
   */
  @ParameterizedTest
  @ValueSource(strings = {"CREATE_SPACE", "JOIN", "LOGIN", "SAVE_CLIENT", "WITHDRAW", "TRANSFER"})
  void concurrentAccountOperationsCannotRestoreWithdrawnIdentity(String operation)
      throws Exception {
    UUID target =
        spaces.create(owner, new CreateCareSpaceRequest("동시 참여 대상", UUID.randomUUID())).spaceId();
    String url = invitations.issue(owner, target, UUID.randomUUID()).invitationUrl();
    String token = url.substring(url.lastIndexOf('/') + 1);
    String spaceVersion =
        jdbc.queryForObject("SELECT version::text FROM care_space WHERE id=?", String.class, space);
    var deleted = new CompletableFuture<Integer>();
    var waiterPid = new CompletableFuture<Integer>();
    var release = new CountDownLatch(1);
    var pool = dataSource.unwrap(HikariDataSource.class);
    try (var monitor =
            DriverManager.getConnection(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var deletion =
          executor.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          tx -> {
                            int pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                            withdrawals.withdraw(member, command());
                            deleted.complete(pid);
                            try {
                              if (!release.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("test timeout");
                            } catch (InterruptedException ex) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(ex);
                            }
                            return true;
                          }));
      int blocker = deleted.get(10, TimeUnit.SECONDS);
      var pending =
          executor.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          tx -> {
                            waiterPid.complete(
                                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            return switch (operation) {
                              case "CREATE_SPACE" ->
                                  spaces
                                      .create(
                                          member,
                                          new CreateCareSpaceRequest("복원 금지", UUID.randomUUID()))
                                      .spaceId();
                              case "JOIN" ->
                                  invitations
                                      .join(member, token, UUID.randomUUID())
                                      .response()
                                      .membershipId();
                              case "LOGIN" ->
                                  social.findOrCreate(
                                      SocialProvider.KAKAO, member.toString(), "새 회원");
                              case "SAVE_CLIENT" -> {
                                clients.saveAuthorizedClient(
                                    client(member), login(member), null, null);
                                yield member;
                              }
                              case "TRANSFER" ->
                                  memberships
                                      .transfer(
                                          owner,
                                          space,
                                          new TransferOwnershipRequest(
                                              membership, spaceVersion, UUID.randomUUID()))
                                      .ownerMembershipId();
                              default -> {
                                withdrawals.withdraw(member, command());
                                yield member;
                              }
                            };
                          }));
      try {
        assertBlocked(monitor, waiterPid.get(10, TimeUnit.SECONDS), blocker, pending);
      } finally {
        release.countDown();
      }
      assertThat(deletion.get(10, TimeUnit.SECONDS)).isTrue();
      if (operation.equals("LOGIN")) {
        UUID fresh = pending.get(10, TimeUnit.SECONDS);
        assertThat(fresh).isNotEqualTo(member);
        assertThat(count("space_membership", "user_id", fresh)).isZero();
      } else {
        assertThatThrownBy(() -> pending.get(10, TimeUnit.SECONDS))
            .hasCauseInstanceOf(
                operation.equals("SAVE_CLIENT")
                    ? OAuth2AuthenticationException.class
                    : ApiException.class)
            .satisfies(
                ex -> {
                  if (ex.getCause() instanceof ApiException api)
                    assertThat(api.code().code())
                        .isEqualTo(
                            operation.equals("TRANSFER") ? "VERSION_CONFLICT" : "UNAUTHENTICATED");
                });
      }
      assertThat(count("app_user", "id", member)).isZero();
      assertThat(count("space_membership", "user_id", member)).isZero();
    } finally {
      release.countDown();
    }
  }

  private void assertBlocked(Connection monitor, int waiter, int blocker, Future<?> pending)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    try (var query = monitor.prepareStatement("SELECT ?=ANY(pg_blocking_pids(?))")) {
      query.setInt(1, blocker);
      query.setInt(2, waiter);
      while (System.nanoTime() < deadline && !pending.isDone()) {
        try (var result = query.executeQuery()) {
          if (result.next() && result.getBoolean(1)) return;
        }
        Thread.sleep(10);
      }
    }
    fail("Expected a confirmed PostgreSQL lock wait for " + waiter);
  }

  @Test
  void swaggerDocumentsEmptySuccessStrictBodyAndCombinedSessionCsrf() throws Exception {
    var doc =
        mapper.readTree(
            mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var operation = doc.at("/paths/~1api~1users~1me~1withdrawals/post");
    assertThat(operation.at("/responses/204/content").isMissingNode()).isTrue();
    assertThat(operation.at("/security/0").propertyNames())
        .containsExactlyInAnyOrder("SessionCookie", "CsrfToken");
    assertThat(doc.at("/components/schemas/MemberWithdrawalBody/required/0").asText())
        .isEqualTo("clientRequestId");
    assertThat(doc.at("/components/schemas/MemberWithdrawalBody/additionalProperties").asBoolean())
        .isFalse();
  }

  private UUID task() {
    return items
        .create(
            member,
            space,
            new CreateCareItemRequest(
                "TASK",
                "보존할 할 일",
                null,
                new CreateCareItemRequest.TaskFields(null, membership.toString()),
                UUID.randomUUID()))
        .itemId();
  }

  private UUID join(UUID actor, UUID target) {
    var link = invitations.issue(owner, target, UUID.randomUUID()).invitationUrl();
    return invitations
        .join(actor, link.substring(link.lastIndexOf('/') + 1), UUID.randomUUID())
        .response()
        .membershipId();
  }

  private Cookie cookie(UUID user) {
    var session = sessions.createSession();
    ((org.springframework.session.Session) session)
        .setAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            new SecurityContextImpl(login(user)));
    sessions.save(session);
    return new Cookie(
        "HANMAUM_SESSION",
        Base64.getEncoder()
            .encodeToString(
                ((org.springframework.session.Session) session)
                    .getId()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private OAuth2AuthorizedClient client(UUID user) {
    var registration =
        ClientRegistration.withRegistrationId("kakao")
            .clientId("synthetic")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/callback")
            .authorizationUri("http://localhost/authorize")
            .tokenUri("http://localhost/token")
            .userInfoUri("http://localhost/user")
            .userNameAttributeName("id")
            .build();
    return new OAuth2AuthorizedClient(
        registration,
        user.toString(),
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "synthetic-token",
            Instant.now(),
            Instant.now().plusSeconds(300)));
  }

  private ObjectNode command() {
    return mapper.createObjectNode().put("clientRequestId", UUID.randomUUID().toString());
  }

  private ResultActions withdraw(UUID actor) throws Exception {
    return call(actor, command().toString());
  }

  private ResultActions call(UUID actor, String body) throws Exception {
    return mvc.perform(
        post(PATH)
            .with(authentication(login(actor)))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private long count(String table, String column, Object value) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + column + "=?", Long.class, value);
  }

  private void assertCode(Runnable action, String code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ApiException.class, ex -> assertThat(ex.code().code()).isEqualTo(code));
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

  private OAuth2AuthenticationToken login(UUID user) {
    return login(user, user.toString());
  }

  private OAuth2AuthenticationToken login(UUID user, String providerId) {
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    return new OAuth2AuthenticationToken(
        new MemberOAuth2User(
            user, new DefaultOAuth2User(authorities, Map.of("id", providerId), "id")),
        authorities,
        "kakao");
  }
}
