package com.hanmaum.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InvitationMigrationIntegrationTests {
  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void upgradesV3WithoutLosingExistingDataAndEnforcesSameSpaceRelationships() {
    String schema = "invitation_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .target("3")
        .load()
        .migrate();
    var jdbc = new JdbcTemplate(dataSource);
    var user = UUID.randomUUID();
    var space = UUID.randomUUID();
    var membership = UUID.randomUUID();
    var otherSpace = UUID.randomUUID();
    var otherMembership = UUID.randomUUID();
    var receipt = UUID.randomUUID();
    var session = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".app_user (id, display_name, created_at, updated_at) VALUES (?, '기존 회원', now(), now())",
        user);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".social_account (id, user_id, provider, provider_user_id, created_at) VALUES (?, ?, 'GOOGLE', 'existing', now())",
        UUID.randomUUID(),
        user);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".spring_session (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name) VALUES (?, ?, 1, 1, 100, 101, ?)",
        session.toString(),
        UUID.randomUUID().toString(),
        user.toString());
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            tx -> {
              jdbc.update(
                  "INSERT INTO "
                      + schema
                      + ".care_space (id, owner_membership_id, owner_user_id, subject_label, created_at, updated_at) VALUES (?, ?, ?, '엄마', now(), now()), (?, ?, ?, '아빠', now(), now())",
                  space,
                  membership,
                  user,
                  otherSpace,
                  otherMembership,
                  user);
              jdbc.update(
                  "INSERT INTO "
                      + schema
                      + ".space_membership (id, space_id, user_id, joined_at) VALUES (?, ?, ?, now()), (?, ?, ?, now())",
                  membership,
                  space,
                  user,
                  otherMembership,
                  otherSpace,
                  user);
              jdbc.update(
                  "INSERT INTO "
                      + schema
                      + ".command_receipt (id, actor_user_id, client_request_id, command_kind, scope_key, request_digest, status, result_refs, created_at, completed_at) VALUES (?, ?, ?, 'CREATE_CARE_SPACE', 'GLOBAL', 'existing-digest', 'SUCCEEDED', '{}'::jsonb, now(), now())",
                  receipt,
                  user,
                  UUID.randomUUID());
            });
    var upgraded =
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load();
    upgraded.migrate();
    upgraded.validate();
    assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("4");
    assertThat(
            jdbc.queryForObject(
                "SELECT display_name FROM " + schema + ".app_user WHERE id = ?",
                String.class,
                user))
        .isEqualTo("기존 회원");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".social_account WHERE user_id = ?",
                Integer.class,
                user))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT principal_name FROM " + schema + ".spring_session WHERE primary_id = ?",
                String.class,
                session.toString()))
        .isEqualTo(user.toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM " + schema + ".care_space WHERE id = ?", Long.class, space))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT invitation_id FROM " + schema + ".space_membership WHERE id = ?",
                UUID.class,
                membership))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT request_digest FROM " + schema + ".command_receipt WHERE id = ?",
                String.class,
                receipt))
        .isEqualTo("existing-digest");
    String insert =
        "INSERT INTO "
            + schema
            + ".invitation (id, space_id, issued_by_membership_id, token_hash, encrypted_url, issued_at, expires_at) VALUES (?, ?, ?, ?, 'encrypted', now(), now() + interval '24 hours')";
    assertThatThrownBy(
            () -> jdbc.update(insert, UUID.randomUUID(), space, otherMembership, "a".repeat(64)))
        .isInstanceOf(DataIntegrityViolationException.class);
    var invitation = UUID.randomUUID();
    jdbc.update(insert, invitation, space, membership, "b".repeat(64));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE " + schema + ".space_membership SET invitation_id = ? WHERE id = ?",
                    invitation,
                    otherMembership))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE " + schema + ".invitation SET expires_at = issued_at WHERE id = ?",
                    invitation))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.update(insert, UUID.randomUUID(), space, membership, "b".repeat(64)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
