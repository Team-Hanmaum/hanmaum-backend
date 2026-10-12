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
class SourceRecordMigrationIntegrationTests {
  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void upgradesV4PreservingExistingDataAndEnforcesSourceRecordConstraints() {
    String schema = "record_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .target("4")
        .load()
        .migrate();
    var jdbc = new JdbcTemplate(dataSource);
    UUID user = UUID.randomUUID();
    UUID space = UUID.randomUUID();
    UUID membership = UUID.randomUUID();
    UUID secondSpace = UUID.randomUUID();
    UUID secondMembership = UUID.randomUUID();
    UUID invitation = UUID.randomUUID();
    UUID receipt = UUID.randomUUID();
    UUID session = UUID.randomUUID();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            tx -> {
              // Generated test schema only; SET LOCAL is reset at the transaction boundary.
              jdbc.execute("SET LOCAL search_path TO " + schema);
              jdbc.update(
                  "INSERT INTO app_user (id, display_name, created_at, updated_at) VALUES (?, '기존 회원', now(), now())",
                  user);
              jdbc.update(
                  "INSERT INTO social_account (id, user_id, provider, provider_user_id, created_at) VALUES (?, ?, 'KAKAO', 'existing', now())",
                  UUID.randomUUID(),
                  user);
              jdbc.update(
                  "INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name) VALUES (?, ?, 1, 1, 100, 101, ?)",
                  session.toString(),
                  UUID.randomUUID().toString(),
                  user.toString());
              jdbc.update(
                  "INSERT INTO care_space (id, owner_membership_id, owner_user_id, subject_label, created_at, updated_at) VALUES (?, ?, ?, '엄마', now(), now()), (?, ?, ?, '아빠', now(), now())",
                  space,
                  membership,
                  user,
                  secondSpace,
                  secondMembership,
                  user);
              jdbc.update(
                  "INSERT INTO space_membership (id, space_id, user_id, joined_at) VALUES (?, ?, ?, now()), (?, ?, ?, now())",
                  membership,
                  space,
                  user,
                  secondMembership,
                  secondSpace,
                  user);
              jdbc.update(
                  "INSERT INTO invitation (id, space_id, issued_by_membership_id, token_hash, encrypted_url, issued_at, expires_at) VALUES (?, ?, ?, ?, 'existing-ciphertext', now(), now() + interval '24 hours')",
                  invitation,
                  space,
                  membership,
                  "a".repeat(64));
              jdbc.update(
                  "INSERT INTO command_receipt (id, actor_user_id, client_request_id, command_kind, scope_key, request_digest, status, result_refs, created_at, completed_at) VALUES (?, ?, ?, 'CREATE_CARE_SPACE', 'SELF', 'existing-digest', 'SUCCEEDED', '{}'::jsonb, now(), now())",
                  receipt,
                  user,
                  UUID.randomUUID());
            });
    var upgraded =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .target("5")
            .load();
    upgraded.migrate();
    upgraded.validate();
    assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("5");
    assertThat(
            jdbc.queryForObject(
                "SELECT display_name FROM " + schema + ".app_user WHERE id = ?",
                String.class,
                user))
        .isEqualTo("기존 회원");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".social_account", Integer.class))
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
                "SELECT count(*) FROM " + schema + ".space_membership", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT encrypted_url FROM " + schema + ".invitation WHERE id = ?",
                String.class,
                invitation))
        .isEqualTo("existing-ciphertext");
    assertThat(
            jdbc.queryForObject(
                "SELECT request_digest FROM " + schema + ".command_receipt WHERE id = ?",
                String.class,
                receipt))
        .isEqualTo("existing-digest");
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM " + schema + ".source_record", Integer.class))
        .isZero();
    String insert =
        "INSERT INTO "
            + schema
            + ".source_record (id, space_id, author_membership_id, body, recorded_at) VALUES (?, ?, ?, '원문', now())";
    assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), space, secondMembership))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), space, null))
        .isInstanceOf(DataIntegrityViolationException.class);
    UUID record = UUID.randomUUID();
    jdbc.update(insert, record, space, membership);
    for (String update :
        new String[] {
          "body = NULL",
          "recorded_at = NULL",
          "version = 0",
          "lifecycle = 'ERASED', erased_at = now()"
        }) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE " + schema + ".source_record SET " + update + " WHERE id = ?",
                      record))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
    jdbc.update(
        "UPDATE " + schema + ".source_record SET create_command_id = ? WHERE id = ?",
        receipt,
        record);
    UUID secondRecord = UUID.randomUUID();
    jdbc.update(insert, secondRecord, space, membership);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE " + schema + ".source_record SET create_command_id = ? WHERE id = ?",
                    receipt,
                    secondRecord))
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update(
        "UPDATE "
            + schema
            + ".source_record SET lifecycle = 'ERASED', body = NULL, recorded_at = NULL, author_membership_id = NULL, erased_at = now(), version = 2 WHERE id = ?",
        record);
    assertThat(
            jdbc.queryForObject(
                "SELECT body FROM " + schema + ".source_record WHERE id = ?", String.class, record))
        .isNull();
  }
}
